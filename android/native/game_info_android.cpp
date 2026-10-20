// What the runtime asks of the game on Android: the same pad rules as the Windows build (src/common/game_info.cpp)
// with the touch controls in place of the keyboard.
#include <atomic>
#include <chrono>
#include <cstring>

#include "app/app.h"
#include "core/input.h"
#include "core/log.h"
#include "touch.h"

namespace {

// The game waits through its logos for the first 12 seconds: only Start gets through until it is pressed
// once, then everything does (the Windows build does the same).
bool Booting(bool start) {
    static const auto t0 = std::chrono::steady_clock::now();
    static std::atomic<bool> skipped{false};
    if (skipped.load(std::memory_order_relaxed)) return false;
    if (start) {
        skipped.store(true, std::memory_order_relaxed);
        return false;
    }
    return std::chrono::steady_clock::now() - t0 < std::chrono::seconds(12);
}

void Neutral(input::Pad& pad, bool keep_start) {
    pad.buttons = keep_start ? (pad.buttons & 0x0010) : 0;
    std::memset(pad.analog, 0, sizeof(pad.analog));
    pad.lx = pad.ly = pad.rx = pad.ry = 0;
}

// port 0: the touch controls and the first controller together, always connected so the game starts without a pad;
// ports 1 to 3: the next controllers, kept connected so versus mode is offered as on Windows
void OnPad(int port, input::Pad& pad) {
    if (port < 0 || port > 3) return;
    if (port == 0) touch::Apply(pad);
    pad.connected = true;
    bool start = (pad.buttons & 0x0010) != 0;
    if (Booting(start && port == 0)) Neutral(pad, true);
}

}  // namespace

const app::GameInfo app::kGame = {
    .title = L"Primal Fury",
    .short_title = L"Primal Fury",
    .name = L"PrimalFury",
    .log = "primal_fury",
    .settings = "settings.toml",
    .splash = L"splash",
    .missing = L"Put the game in Internal storage/PrimalFury: its disc image (.iso), or default.xbe with its folders.",
    .images = {{"default.xbe", "PrimalFury"}},
    .options = {},
    .load = nullptr,
    .save = nullptr,
    .init = nullptr,
    .loaded = nullptr,
    .window = nullptr,
    .message = nullptr,
    .ui = [](double) { touch::Draw(); },
    .pad = OnPad,
    .rumble = nullptr,
};
