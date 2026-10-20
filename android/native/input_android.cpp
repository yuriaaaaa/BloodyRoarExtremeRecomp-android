// Controllers through SDL on Android: the same report as the SDK's input.cpp, but the window thread owns SDL's
// event queue (app_android.cpp) and tells this file when a pad comes or goes, instead of each poll draining it.
#include "core/input.h"

#include <algorithm>
#include <iterator>
#include <mutex>

#include <SDL3/SDL.h>

#include "app/app.h"
#include "core/log.h"
#include "touch.h"

namespace input {

namespace {

// a controller takes the lowest free slot when it is plugged in and keeps it until it is unplugged
std::mutex g_lock;
SDL_Gamepad* g_pads[4] = {};

void UpdateTouch() {
    bool any = std::any_of(std::begin(g_pads), std::end(g_pads), [](SDL_Gamepad* p) { return p != nullptr; });
    touch::SetControllerConnected(any);
}

}  // namespace

// window thread
void AddPad(SDL_JoystickID id) {
    std::lock_guard lock(g_lock);
    for (SDL_Gamepad* pad : g_pads)
        if (pad && SDL_GetGamepadID(pad) == id) return;
    SDL_Gamepad** slot = std::find(std::begin(g_pads), std::end(g_pads), nullptr);
    if (slot == std::end(g_pads)) return;
    *slot = SDL_OpenGamepad(id);
    const char* name = *slot ? SDL_GetGamepadName(*slot) : SDL_GetError();
    LOGI("input: controller %d %s: %s", int(slot - g_pads) + 1, *slot ? "plugged in" : "failed to open", name ? name : "");
    UpdateTouch();
}

void RemovePad(SDL_JoystickID id) {
    std::lock_guard lock(g_lock);
    for (int i = 0; i < 4; i++) {
        if (!g_pads[i] || SDL_GetGamepadID(g_pads[i]) != id) continue;
        SDL_CloseGamepad(g_pads[i]);
        g_pads[i] = nullptr;
        LOGI("input: controller %d unplugged", i + 1);
    }
    UpdateTouch();
}

void Init() {
    int count = 0;
    if (SDL_JoystickID* ids = SDL_GetGamepads(&count)) {
        for (int i = 0; i < count; i++) AddPad(ids[i]);
        SDL_free(ids);
    }
}

// the physical controller in SDL slot `port`, with no game hook (game_info_android.cpp maps slots to ports)
Pad PollSlot(int port) {
    Pad p;
    std::lock_guard lock(g_lock);
    SDL_Gamepad* pad = port >= 0 && port < 4 ? g_pads[port] : nullptr;
    if (!pad) return p;
    p.connected = true;
    constexpr SDL_GamepadButton kButtons[] = {
        SDL_GAMEPAD_BUTTON_DPAD_UP,    SDL_GAMEPAD_BUTTON_DPAD_DOWN, SDL_GAMEPAD_BUTTON_DPAD_LEFT,
        SDL_GAMEPAD_BUTTON_DPAD_RIGHT, SDL_GAMEPAD_BUTTON_START,     SDL_GAMEPAD_BUTTON_BACK,
        SDL_GAMEPAD_BUTTON_LEFT_STICK, SDL_GAMEPAD_BUTTON_RIGHT_STICK};
    for (int i = 0; i < 8; i++) p.buttons |= uint16_t(SDL_GetGamepadButton(pad, kButtons[i]) << i);
    // A B X Y black white
    constexpr SDL_GamepadButton kAnalog[] = {SDL_GAMEPAD_BUTTON_SOUTH, SDL_GAMEPAD_BUTTON_EAST,
                                             SDL_GAMEPAD_BUTTON_WEST,  SDL_GAMEPAD_BUTTON_NORTH,
                                             SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER};
    for (int i = 0; i < 6; i++) p.analog[i] = SDL_GetGamepadButton(pad, kAnalog[i]) ? 0xFF : 0;
    auto axis = [&](SDL_GamepadAxis a) { return int(SDL_GetGamepadAxis(pad, a)); };
    p.analog[6] = uint8_t(std::max(axis(SDL_GAMEPAD_AXIS_LEFT_TRIGGER), 0) >> 7);
    p.analog[7] = uint8_t(std::max(axis(SDL_GAMEPAD_AXIS_RIGHT_TRIGGER), 0) >> 7);
    // SDL's sticks have y down; not plain negation: -32768 has no positive
    p.lx = int16_t(axis(SDL_GAMEPAD_AXIS_LEFTX));
    p.ly = int16_t(-1 - axis(SDL_GAMEPAD_AXIS_LEFTY));
    p.rx = int16_t(axis(SDL_GAMEPAD_AXIS_RIGHTX));
    p.ry = int16_t(-1 - axis(SDL_GAMEPAD_AXIS_RIGHTY));
    return p;
}

Pad Poll(int port) {
    Pad p = PollSlot(port);
    if (app::kGame.pad) app::kGame.pad(port, p);
    return p;
}

void RumbleSlot(int slot, uint16_t left, uint16_t right) {
    std::lock_guard lock(g_lock);
    if (slot >= 0 && slot < 4 && g_pads[slot]) SDL_RumbleGamepad(g_pads[slot], left, right, 0);
}

// the motors run until set again; port n is SDL slot n (port 0 is also the touch controls)
void Rumble(int port, uint16_t left, uint16_t right) {
    if (app::kGame.rumble) app::kGame.rumble(port, left, right);
    RumbleSlot(port, left, right);
}

}  // namespace input
