// The Android host: SDL's window and event loop in place of the Win32 one (sdk/runtime/app/main.cpp), the settings
// the launcher passes in the environment, and the game found in the folder the player picked.
//
// Environment (PrimalFuryActivity.java):
//   PRIMALFURY_DIR       game folder: a disc image (.iso), or default.xbe with the disc's folders beside it
//   PRIMALFURY_DATA      Internal storage/PrimalFury: saves (hdd) and, with debug logging, logs
//   PRIMALFURY_CACHE     the app's own folder: shader and pipeline caches
//   PRIMALFURY_TOUCH     touch controls file (touch.cpp)
//   PRIMALFURY_SCALE     internal resolution, times the game's 640x480 (1 to 4)
//   PRIMALFURY_ASPECT    4:3 or 16:9 (the game is told it has a widescreen TV)
//   PRIMALFURY_VSYNC     0 or 1;  PRIMALFURY_FPS: frame cap, 0 for none
//   PRIMALFURY_DEBUG     1 writes every log line to PRIMALFURY_DATA/logs
#include <algorithm>
#include <cctype>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <thread>
#include <unistd.h>

#include <android/log.h>
#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <imgui.h>
#include <backends/imgui_impl_sdl3.h>

#include "app/app.h"
#include "core/input.h"
#include "core/log.h"
#include "gpu/vulkan.h"
#include "image_symbols.h"
#include "kernel/crypto.h"
#include "kernel/disc.h"
#include "kernel/kernel.h"
#include "touch.h"

extern const char kImageSha1[];  // the xbe recompiled into this library, written by CMake

namespace input {
void AddPad(SDL_JoystickID id);
void RemovePad(SDL_JoystickID id);
}  // namespace input

namespace app {

namespace {

namespace fs = std::filesystem;

SDL_Window* g_window = nullptr;
float g_aspect = 4.0f / 3.0f;

std::string Env(const char* name, const char* fallback = "") {
    const char* v = std::getenv(name);
    return v && *v ? v : fallback;
}

fs::path DataDir() { return Env("PRIMALFURY_DATA", "/sdcard/PrimalFury"); }

std::string Lower(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) { return char(std::tolower(c)); });
    return s;
}

std::string Sha1(const std::vector<uint8_t>& data) {
    alignas(8) uint8_t ctx[128];
    uint8_t digest[20];
    crypto::ShaInit(ctx);
    crypto::ShaUpdate(ctx, data.data(), data.size());
    crypto::ShaFinal(ctx, digest);
    std::string hex;
    for (uint8_t b : digest) {
        hex += "0123456789abcdef"[b >> 4];
        hex += "0123456789abcdef"[b & 15];
    }
    return hex;
}

bool ReadHostFile(const fs::path& path, std::vector<uint8_t>& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out.assign(std::istreambuf_iterator<char>(f), {});
    return true;
}

void Message(const char* text) {
    LOGE("%s", text);
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "Primal Fury", text, g_window);
}

// a crash on a guest thread: what it was running, into the log, before Android's own report
void OnCrash(int sig, siginfo_t* info, void*) {
    if (kernel::Thread* t = kernel::Current(); t && t->ctx) {
        LOGE("signal %d at %p on guest thread %X", sig, info ? info->si_addr : nullptr, t->id);
        if (info && (sig == SIGSEGV || sig == SIGBUS)) {
            uintptr_t va = reinterpret_cast<uintptr_t>(info->si_addr), g = reinterpret_cast<uintptr_t>(kernel::g_base);
            if (va >= g && va - g < (uintptr_t(1) << 32)) LOGE("  guest address %08llX", (unsigned long long)(va - g));
        }
        kernel::LogGuestStack(*t->ctx, 256);
    } else {
        LOGE("signal %d at %p on a host thread", sig, info ? info->si_addr : nullptr);
    }
    logx::Flush();
    __android_log_print(ANDROID_LOG_FATAL, "PrimalFury", "crashed (signal %d), see the log", sig);
    signal(sig, SIG_DFL);
    raise(sig);
}

void InstallCrashHandler() {
    struct sigaction sa {};
    sa.sa_sigaction = OnCrash;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (int sig : {SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT}) sigaction(sig, &sa, nullptr);
}

// the activity going to the background takes its window with it; the renderer waits for the new one
bool SDLCALL Lifecycle(void*, SDL_Event* e) {
    switch (e->type) {
        case SDL_EVENT_WILL_ENTER_BACKGROUND:
        case SDL_EVENT_DID_ENTER_BACKGROUND:
            gpu::g_paused.store(true, std::memory_order_release);
            touch::Cancel();
            break;
        case SDL_EVENT_DID_ENTER_FOREGROUND:
            touch::PollConfig();
            gpu::g_paused.store(false, std::memory_order_release);
            break;
        default:
            break;
    }
    return true;
}

}  // namespace

float DisplayAspect() { return touch::Stretch() ? 0.0f : g_aspect; }

Settings& Get() {
    static Settings s;
    return s;
}

void Load() {
    Settings& s = Get();
    s.display_mode = DisplayMode::Fullscreen;
    s.render_percent = 100;
    s.vsync = Env("PRIMALFURY_VSYNC", "1") != "0";
    s.fps_cap = uint32_t(std::atoi(Env("PRIMALFURY_FPS", "60").c_str()));
    g_aspect = Env("PRIMALFURY_ASPECT", "4:3") == "16:9" ? 16.0f / 9.0f : 4.0f / 3.0f;
}
void Save() {}

void LogSettings() {
    const Settings& s = Get();
    LOGR("Settings: scale %.0fx, aspect %s%s, vsync %d, fps cap %u", RenderScale(480), Env("PRIMALFURY_ASPECT", "4:3").c_str(),
         touch::Stretch() ? " stretched" : "", s.vsync, s.fps_cap);
}

void LogSystem() {
    int v = SDL_GetVersion();
    LOGR("System: Android, SDL %d.%d.%d, game folder %s", SDL_VERSIONNUM_MAJOR(v), SDL_VERSIONNUM_MINOR(v), SDL_VERSIONNUM_MICRO(v),
         Env("PRIMALFURY_DIR").c_str());
    LogSettings();
}

void ApplyDisplay() {}

Mode Desktop() {
    int w = 0, h = 0;
    if (g_window) SDL_GetWindowSizeInPixels(g_window, &w, &h);
    return {uint32_t(w > 0 ? w : 1920), uint32_t(h > 0 ? h : 1080)};
}
Mode Output() { return Desktop(); }

// the Vulkan backend takes this as a multiplier of the game's own resolution
float RenderScale(uint32_t) {
    int s = std::atoi(Env("PRIMALFURY_SCALE", "2").c_str());
    return float(std::clamp(s, 1, 4));
}

bool Widescreen() { return g_aspect > 1.5f; }

fs::path ExeDir() { return DataDir(); }

bool FindGame(GameFiles& game) {
    fs::path dir = Env("PRIMALFURY_DIR", DataDir().c_str());
    std::error_code ec;
    std::vector<fs::path> isos;
    for (auto& e : fs::directory_iterator(dir, ec))
        if (e.is_regular_file(ec) && Lower(e.path().extension().string()) == ".iso") isos.push_back(e.path());
    std::sort(isos.begin(), isos.end());

    bool other = false;
    auto consider = [&](const fs::path& name, std::vector<uint8_t> xbe, std::unique_ptr<kernel::Disc> disc) {
        std::string sha = Sha1(xbe);
        bool match = sha == kImageSha1;
        LOGR("Game: %s in %s, sha1 %s%s", IMAGE_XBE, name.string().c_str(), sha.c_str(), match ? "" : ", another release");
        if (!match) {
            other = true;
            return false;
        }
        game = {dir, std::move(xbe)};
        kernel::MountDisc(std::move(disc));
        return true;
    };
    std::vector<uint8_t> xbe;
    if (ReadHostFile(dir / IMAGE_XBE, xbe) && consider(dir, std::move(xbe), nullptr)) return true;
    for (const fs::path& iso : isos) {
        auto disc = std::make_unique<kernel::Disc>();
        std::string error;
        if (!disc->Open(iso, error)) {
            LOGW("game: %s: %s", iso.string().c_str(), error.c_str());
            continue;
        }
        if (!disc->Load(IMAGE_XBE, xbe)) {
            LOGW("game: %s has no %s", iso.string().c_str(), IMAGE_XBE);
            continue;
        }
        if (consider(iso, std::move(xbe), std::move(disc))) return true;
    }
    Message(other ? "The game in this folder is a different release than this build was made from (it needs the USA "
                    "Bloody Roar Extreme disc). Pick the USA copy in the launcher."
                  : "No game found. Put the disc image (.iso), or default.xbe with the disc's folders, in the game "
                    "folder chosen in the launcher.");
    return false;
}

int Instance() { return 0; }
void* Window() { return g_window; }

bool OpenSplash() { return false; }
void SplashFirstFrame() {}
void CloseSplash() {}
void OpenGameWindow(bool) {}
bool StartHandover() { return false; }
bool HandingOver() { return false; }
void FinishHandover() {}

std::recursive_mutex& UiLock() {
    static std::recursive_mutex m;
    return m;
}

void Pace(uint32_t max_fps) {
    using clock = std::chrono::steady_clock;
    static clock::time_point next = clock::now();
    uint32_t cap = Get().fps_cap;
    if (max_fps && (!cap || max_fps < cap)) cap = max_fps;
    auto now = clock::now();
    if (!cap) {
        next = now;
        return;
    }
    auto period = std::chrono::nanoseconds(1000000000ull / cap);
    next += period;
    if (next < now - period) next = now;
    while (clock::now() + std::chrono::milliseconds(2) < next) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    while (clock::now() < next) std::this_thread::yield();
}

}  // namespace app

int main(int, char**) {
    namespace fs = std::filesystem;
    std::error_code ec;
    fs::path data = app::DataDir();
    fs::create_directories(data, ec);
    // the renderer keeps its caches in the working folder
    if (std::string cache = app::Env("PRIMALFURY_CACHE"); !cache.empty()) {
        fs::create_directories(cache, ec);
        if (chdir(cache.c_str()) != 0) __android_log_print(ANDROID_LOG_WARN, "PrimalFury", "chdir %s failed", cache.c_str());
    }
    bool debug = app::Env("PRIMALFURY_DEBUG") == "1";
    logx::kVerbose = debug;
    // errors only go to the app's own folder unless debug logging is on
    logx::Init(debug ? (data / "logs").string() : fs::current_path(ec).string(), app::kGame.log);
    app::InstallCrashHandler();
    app::Load();
    app::LogSystem();

    SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
    SDL_SetHint(SDL_HINT_MOUSE_TOUCH_EVENTS, "0");
    SDL_SetHint(SDL_HINT_ANDROID_TRAP_BACK_BUTTON, "0");
    SDL_SetHint(SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
    if (!SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMEPAD | SDL_INIT_EVENTS)) {
        __android_log_print(ANDROID_LOG_FATAL, "PrimalFury", "SDL_Init: %s", SDL_GetError());
        LOGE("SDL_Init failed: %s", SDL_GetError());
        return 1;
    }
    SDL_AddEventWatch(app::Lifecycle, nullptr);
    app::g_window = SDL_CreateWindow("Primal Fury", 0, 0, SDL_WINDOW_FULLSCREEN | SDL_WINDOW_VULKAN | SDL_WINDOW_HIGH_PIXEL_DENSITY);
    if (!app::g_window) {
        LOGE("SDL_CreateWindow failed: %s", SDL_GetError());
        return 1;
    }

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    ImFontConfig fc;
    fc.SizePixels = 48.0f;  // the touch labels are drawn large; one big font keeps them sharp
    io.Fonts->AddFontDefault(&fc);
    ImGui_ImplSDL3_InitForVulkan(app::g_window);

    input::Init();
    app::GameFiles game;
    if (!app::FindGame(game)) {
        logx::Flush();
        return 1;
    }
    if (!kernel::Boot(std::move(game.xbe), IMAGE_XBE, game.dir.string(), (data / "hdd").string())) {
        app::Message("The game failed to start. Turn on Debug logging in the launcher and look in PrimalFury/logs.");
        logx::Flush();
        return 1;
    }

    uint64_t last_flush = SDL_GetTicks();
    for (;;) {
        SDL_Event e;
        if (SDL_WaitEventTimeout(&e, 250)) {
            do {
                switch (e.type) {
                    case SDL_EVENT_QUIT:
                    case SDL_EVENT_TERMINATING:
                        logx::Flush();
                        kernel::Shutdown(0);
                    case SDL_EVENT_FINGER_DOWN:
                        touch::Event(touch::kDown, e.tfinger.fingerID, e.tfinger.x, e.tfinger.y);
                        break;
                    case SDL_EVENT_FINGER_MOTION:
                        touch::Event(touch::kMove, e.tfinger.fingerID, e.tfinger.x, e.tfinger.y);
                        break;
                    case SDL_EVENT_FINGER_UP:
                    case SDL_EVENT_FINGER_CANCELED:
                        touch::Event(touch::kUp, e.tfinger.fingerID, e.tfinger.x, e.tfinger.y);
                        break;
                    case SDL_EVENT_GAMEPAD_ADDED: input::AddPad(e.gdevice.which); break;
                    case SDL_EVENT_GAMEPAD_REMOVED: input::RemovePad(e.gdevice.which); break;
                    case SDL_EVENT_WINDOW_RESIZED:
                    case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
                        gpu::g_window_resized.store(true, std::memory_order_relaxed);
                        break;
                    default: break;
                }
            } while (SDL_PollEvent(&e));
        }
        uint64_t now = SDL_GetTicks();
        if (now - last_flush >= 1000) {
            last_flush = now;
            touch::PollConfig();
            logx::Flush();
        }
    }
}
