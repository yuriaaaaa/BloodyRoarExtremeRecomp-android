// Touch controls drawn over the game: a D-pad, A/B/X/Y, White/Black, the triggers, Back and Start.
// The launcher's editor moves and resizes them; its file (PRIMALFURY_TOUCH) overrides the defaults in touch.cpp.
#pragma once
#include <cstdint>

namespace input {
struct Pad;
}

namespace touch {

enum EventType { kDown, kMove, kUp };
// nx, ny: 0..1 across the window
void Event(EventType type, uint64_t finger, float nx, float ny);
void Cancel();  // forget every finger (the settings opened over the game)
// the fingers' buttons and D-pad, added into port 0's report
void Apply(input::Pad& pad);
// ImGui, inside the renderer's frame
void Draw();
// the settings file changed on disk: read it again
void PollConfig();

// display switches kept in the same file, so they apply when the game resumes
bool Stretch();
// a controller is connected: the touch controls step aside (setting)
void SetControllerConnected(bool on);

}  // namespace touch
