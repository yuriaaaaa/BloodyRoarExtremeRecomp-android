// The mixer's output through SDL3 audio: 48 kHz stereo float. Like the XAudio2 output, a thread of its own renders
// small chunks and keeps only a few queued, so the guest's play cursors move smoothly instead of in the device's
// large bursts (streams write just ahead of the cursor and play stale data when it jumps).
#include <algorithm>
#include <chrono>
#include <functional>
#include <thread>
#include <vector>

#include <SDL3/SDL.h>

#include "audio/audio.h"
#include "core/log.h"

namespace audio {

void StartOutput(std::function<void(float*, uint32_t)> render) {
    SDL_SetHint(SDL_HINT_AUDIO_DEVICE_SAMPLE_FRAMES, "480");
    if (!SDL_InitSubSystem(SDL_INIT_AUDIO)) {
        LOGE("audio: SDL audio failed to start: %s", SDL_GetError());
        return;
    }
    SDL_AudioSpec spec{SDL_AUDIO_F32, 2, int(kRate)};
    SDL_AudioStream* s = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &spec, nullptr, nullptr);
    if (!s) {
        LOGE("audio: no output device: %s", SDL_GetError());
        return;
    }
    SDL_AudioSpec dev{};
    int dev_frames = 0;
    SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(s), &dev, &dev_frames);
    constexpr uint32_t kChunk = kRate / 100;  // 10 ms
    // enough for one device pull plus a chunk of slack, at least 30 ms
    uint32_t keep = std::max<uint32_t>(3 * kChunk, uint32_t(dev_frames) * (kRate / std::max(dev.freq, 1)) + kChunk);
    LOGI("audio: SDL %s, 48000 Hz stereo (device %d Hz, %d frames a pull, %u ms queued)", SDL_GetCurrentAudioDriver(),
         dev.freq, dev_frames, keep * 1000 / kRate);
    SDL_ResumeAudioStreamDevice(s);
    std::thread([s, keep, render = std::move(render)] {
        SDL_SetCurrentThreadPriority(SDL_THREAD_PRIORITY_TIME_CRITICAL);
        std::vector<float> buf(size_t(kChunk) * 2);
        constexpr int kFrameBytes = 2 * sizeof(float);
        // chunks go out on the wall clock, not when the device drains a burst, so the guest's cursors advance
        // at an even rate; the queue level only corrects drift between the two clocks
        using clock = std::chrono::steady_clock;
        const auto start = clock::now();
        uint64_t made = 0;
        for (;;) {
            int queued_bytes = SDL_GetAudioStreamQueued(s);
            if (queued_bytes < 0) break;
            uint32_t queued = uint32_t(queued_bytes / kFrameBytes);
            uint64_t due = uint64_t(std::chrono::duration<double>(clock::now() - start).count() * kRate) + keep;
            if (queued >= 2 * keep) {  // the device drains slower than the clock: drop the debt
                made = std::max(made, due);
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            if (queued < kChunk) {
                made = std::max(made, due);  // faster than the clock, or a stall: refill at once
            } else if (made >= due) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            render(buf.data(), kChunk);
            SDL_PutAudioStreamData(s, buf.data(), int(kChunk) * kFrameBytes);
            made += kChunk;
        }
    }).detach();
}

}  // namespace audio
