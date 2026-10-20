// Touch controls laid out like an Xbox pad for a fighter: a D-pad under the left thumb (eight ways, slide to change
// direction), the A/B/X/Y diamond under the right thumb (slide across it to chain buttons), White and Black above
// them, the triggers in the top corners, Back and Start in the middle.
#include "touch.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/stat.h>

#include <SDL3/SDL.h>
#include <imgui.h>

#include "app/app.h"
#include "core/input.h"

namespace touch {

namespace {

enum Btn { kA, kB, kX, kY, kWhite, kBlack, kLT, kRT, kBack, kStart, kDpad, kCount };
const char* const kNames[kCount] = {"A", "B", "X", "Y", "WHITE", "BLACK", "LT", "RT", "BACK", "START", "DPAD"};
const char* const kLabels[kCount] = {"A", "B", "X", "Y", "W", "Bk", "LT", "RT", "Back", "Start", ""};
// Xbox colours: green A, red B, blue X, yellow Y, white and black, grey for the rest
const ImU32 kColours[kCount] = {IM_COL32(89, 217, 89, 255),  IM_COL32(242, 89, 89, 255),  IM_COL32(89, 153, 255, 255),
                                IM_COL32(255, 217, 64, 255), IM_COL32(240, 240, 240, 255), IM_COL32(60, 60, 60, 255),
                                IM_COL32(217, 217, 217, 255), IM_COL32(217, 217, 217, 255), IM_COL32(217, 217, 217, 255),
                                IM_COL32(217, 217, 217, 255), IM_COL32(217, 217, 217, 255)};

struct Button {
    float x, y, r;  // screen heights
};

std::mutex g_lock;
Button g_btn[kCount];
struct Override {
    bool set;
    float fx, fy, r;  // centre as fractions of the width and height, radius in heights
} g_ovr[kCount];
float g_opacity = 0.6f, g_scale = 1.0f;
bool g_show = true, g_stretch = false, g_padhide = true, g_loaded = false, g_layout = false;
bool g_controller = false;
float g_w = 1, g_h = 1;  // window, pixels

constexpr int kMaxFingers = 10;
struct Finger {
    bool used;
    uint64_t id;
    int role;  // a Btn, -1 none
    float x, y;
} g_f[kMaxFingers];

void LoadConfig() {
    g_loaded = true;
    std::memset(g_ovr, 0, sizeof(g_ovr));
    g_opacity = 0.6f;
    g_scale = 1.0f;
    g_show = true;
    g_stretch = false;
    g_padhide = true;
    const char* path = std::getenv("PRIMALFURY_TOUCH");
    FILE* f = path ? std::fopen(path, "r") : nullptr;
    if (!f) return;
    char line[160], name[24];
    float a, b, c;
    while (std::fgets(line, sizeof(line), f)) {
        if (std::sscanf(line, "btn %23s %f %f %f", name, &a, &b, &c) == 4) {
            for (int i = 0; i < kCount; i++)
                if (!std::strcmp(name, kNames[i])) g_ovr[i] = {true, a, b, c};
        } else if (std::sscanf(line, "opacity %f", &a) == 1) {
            g_opacity = a < 0.05f ? 0.05f : a > 1 ? 1 : a;
        } else if (std::sscanf(line, "scale %f", &a) == 1) {
            g_scale = a < 0.4f ? 0.4f : a > 2.5f ? 2.5f : a;
        } else if (std::sscanf(line, "show %f", &a) == 1) {
            g_show = a != 0;
        } else if (std::sscanf(line, "stretch %f", &a) == 1) {
            g_stretch = a != 0;
        } else if (std::sscanf(line, "padhide %f", &a) == 1) {
            g_padhide = a != 0;
        }
    }
    std::fclose(f);
}

void Layout() {
    if (!g_loaded) LoadConfig();
    float aspect = g_w / g_h, il = 0, ir = 0;
    SDL_Rect safe;
    if (auto* w = static_cast<SDL_Window*>(app::Window()); w && SDL_GetWindowSafeArea(w, &safe)) {
        int ww = 0, wh = 0;
        SDL_GetWindowSize(w, &ww, &wh);
        if (wh > 0) {
            il = float(safe.x) / float(wh);
            ir = float(ww - safe.x - safe.w) / float(wh);
        }
    }
    float L = std::fmax(il, 0.03f), R = aspect - std::fmax(ir, 0.03f), C = aspect * 0.5f;
    // the same defaults as TouchConfig.defaults() in the launcher
    const Button d[kCount] = {
        {R - 0.25f, 0.82f, 0.085f}, {R - 0.10f, 0.67f, 0.085f}, {R - 0.40f, 0.67f, 0.085f}, {R - 0.25f, 0.52f, 0.085f},
        {L + 0.10f, 0.32f, 0.065f}, {R - 0.10f, 0.32f, 0.065f}, {L + 0.10f, 0.13f, 0.065f}, {R - 0.10f, 0.13f, 0.065f},
        {C - 0.12f, 0.10f, 0.050f}, {C + 0.12f, 0.10f, 0.050f}, {L + 0.24f, 0.70f, 0.180f},
    };
    for (int b = 0; b < kCount; b++) {
        g_btn[b] = d[b];
        if (g_ovr[b].set) g_btn[b] = {g_ovr[b].fx * aspect, g_ovr[b].fy, g_ovr[b].r};
        g_btn[b].r *= g_scale;
        if (!g_show) g_btn[b].r = 0;
    }
    g_layout = true;
}

bool Hidden() { return g_padhide && g_controller; }

int Hit(float x, float y, bool face_only) {
    for (int b = 0; b < kCount; b++) {
        if (face_only && b > kY) break;
        float dx = x - g_btn[b].x, dy = y - g_btn[b].y, rr = g_btn[b].r * (b == kDpad ? 1.15f : 1.25f);
        if (g_btn[b].r > 0 && dx * dx + dy * dy <= rr * rr) return b;
    }
    return -1;
}

// the D-pad's directions for a finger: 8 sectors around the centre, a small dead zone in the middle
uint16_t DpadBits(float x, float y) {
    const Button& d = g_btn[kDpad];
    float dx = x - d.x, dy = y - d.y;
    if (dx * dx + dy * dy < (d.r * 0.22f) * (d.r * 0.22f)) return 0;
    float a = std::atan2(-dy, dx);  // up is positive
    int sector = int(std::lround(a / (float(M_PI) / 4.0f))) & 7;  // 0 right, 2 up, 4 left, 6 down
    static const uint16_t kBits[8] = {8, 8 | 1, 1, 1 | 4, 4, 4 | 2, 2, 2 | 8};  // up 1, down 2, left 4, right 8
    return kBits[sector];
}

}  // namespace

void Event(EventType type, uint64_t finger, float nx, float ny) {
    std::lock_guard lock(g_lock);
    if (auto* w = static_cast<SDL_Window*>(app::Window())) {
        int pw = 0, ph = 0;
        SDL_GetWindowSizeInPixels(w, &pw, &ph);
        if (pw > 0 && ph > 0 && std::fabs(float(pw) / float(ph) - g_w / g_h) > 0.01f) {
            g_w = float(pw);
            g_h = float(ph);
            g_layout = false;
        }
    }
    if (!g_layout) Layout();
    float x = nx * g_w / g_h, y = ny;
    if (type == kDown) {
        int slot = -1;
        for (int i = 0; i < kMaxFingers; i++)
            if (!g_f[i].used) {
                slot = i;
                break;
            }
        if (slot < 0) return;
        g_f[slot] = {true, finger, Hidden() ? -1 : Hit(x, y, false), x, y};
        return;
    }
    for (Finger& f : g_f) {
        if (!f.used || f.id != finger) continue;
        if (type == kUp) {
            f.used = false;
            continue;
        }
        f.x = x;
        f.y = y;
        // a finger on a face button slides onto the next one, as on a real pad
        if (f.role >= kA && f.role <= kY) {
            int b = Hit(x, y, true);
            if (b >= 0) f.role = b;
        }
    }
}

void Cancel() {
    std::lock_guard lock(g_lock);
    std::memset(g_f, 0, sizeof(g_f));
}

void Apply(input::Pad& pad) {
    std::lock_guard lock(g_lock);
    if (!g_layout || Hidden()) return;
    for (const Finger& f : g_f) {
        if (!f.used || f.role < 0) continue;
        switch (f.role) {
            case kDpad: pad.buttons |= DpadBits(f.x, f.y); break;
            case kStart: pad.buttons |= 0x10; break;
            case kBack: pad.buttons |= 0x20; break;
            case kA: case kB: case kX: case kY: pad.analog[f.role - kA] = 0xFF; break;
            case kBlack: pad.analog[4] = 0xFF; break;
            case kWhite: pad.analog[5] = 0xFF; break;
            case kLT: pad.analog[6] = 0xFF; break;
            case kRT: pad.analog[7] = 0xFF; break;
        }
    }
}

void Draw() {
    std::lock_guard lock(g_lock);
    ImGuiIO& io = ImGui::GetIO();
    if (io.DisplaySize.y <= 0) return;
    float w = io.DisplaySize.x, h = io.DisplaySize.y;
    if (!g_layout || std::fabs(w / h - g_w / g_h) > 0.01f) {
        // drawn before any touch: lay out for the frame's shape
        g_w = w;
        g_h = h;
        Layout();
    }
    if (Hidden()) return;
    bool pressed[kCount] = {};
    uint16_t dpad = 0;
    for (const Finger& f : g_f) {
        if (!f.used || f.role < 0) continue;
        pressed[f.role] = true;
        if (f.role == kDpad) dpad |= DpadBits(f.x, f.y);
    }
    ImDrawList* dl = ImGui::GetForegroundDrawList();
    ImFont* font = ImGui::GetFont();
    auto alpha = [](ImU32 c, float a) { return (c & 0x00FFFFFFu) | (ImU32(std::fmin(a, 1.0f) * 255.0f) << 24); };
    for (int b = 0; b < kCount; b++) {
        const Button& t = g_btn[b];
        if (t.r <= 0) continue;
        ImVec2 c(t.x * h, t.y * h);
        float r = t.r * h, a = g_opacity * (pressed[b] ? 1.0f : 0.55f);
        if (b == kDpad) {
            dl->AddCircleFilled(c, r, alpha(IM_COL32(30, 30, 36, 255), g_opacity * 0.6f), 48);
            dl->AddCircle(c, r * 0.97f, alpha(IM_COL32(217, 217, 217, 255), g_opacity), 48, r * 0.04f);
            // four arms, lit by the directions held
            const float arm = r * 0.30f, len = r * 0.82f;
            struct Arm {
                uint16_t bit;
                float dx, dy;
            } arms[4] = {{1, 0, -1}, {2, 0, 1}, {4, -1, 0}, {8, 1, 0}};
            for (const Arm& m : arms) {
                ImVec2 tip(c.x + m.dx * len, c.y + m.dy * len);
                ImVec2 base(c.x + m.dx * arm, c.y + m.dy * arm);
                ImVec2 side(-m.dy * arm, m.dx * arm);
                ImU32 col = alpha(IM_COL32(230, 230, 230, 255), g_opacity * ((dpad & m.bit) ? 1.0f : 0.45f));
                dl->AddQuadFilled(ImVec2(base.x + side.x, base.y + side.y), ImVec2(tip.x + side.x * 0.7f, tip.y + side.y * 0.7f),
                                  ImVec2(tip.x - side.x * 0.7f, tip.y - side.y * 0.7f), ImVec2(base.x - side.x, base.y - side.y), col);
            }
            dl->AddCircleFilled(c, arm, alpha(IM_COL32(230, 230, 230, 255), g_opacity * 0.45f), 24);
            continue;
        }
        dl->AddCircleFilled(c, r, alpha(kColours[b], a * 0.55f), 40);
        dl->AddCircle(c, r * 0.93f, alpha(kColours[b], a), 40, r * 0.08f);
        const char* s = kLabels[b];
        size_t n = std::strlen(s);
        float size = r * (n > 2 ? 0.55f : n > 1 ? 0.75f : 0.95f);
        ImVec2 ts = font->CalcTextSizeA(size, FLT_MAX, 0, s);
        ImU32 text = b == kWhite ? IM_COL32(20, 20, 20, 255) : IM_COL32(255, 255, 255, 255);
        dl->AddText(font, size, ImVec2(c.x - ts.x * 0.5f, c.y - ts.y * 0.5f), alpha(text, g_opacity + 0.25f), s);
    }
}

void PollConfig() {
    static long long last = -1;
    const char* path = std::getenv("PRIMALFURY_TOUCH");
    struct stat st;
    long long stamp = path && ::stat(path, &st) == 0
                          ? ((long long)st.st_mtim.tv_sec * 1000000000LL + st.st_mtim.tv_nsec) ^ (long long)st.st_size
                          : 0;
    if (last >= 0 && stamp != last) {
        std::lock_guard lock(g_lock);
        g_loaded = false;
        g_layout = false;
    }
    last = stamp;
}

bool Stretch() {
    std::lock_guard lock(g_lock);
    if (!g_loaded) LoadConfig();
    return g_stretch;
}

void SetControllerConnected(bool on) {
    std::lock_guard lock(g_lock);
    g_controller = on;
}

}  // namespace touch
