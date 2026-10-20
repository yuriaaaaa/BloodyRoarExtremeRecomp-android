// The native Vulkan backend (vulkan.cpp), on Windows and Android
#pragma once
#include <atomic>
#include <memory>

#include "gpu/backend.h"

namespace gpu {

std::unique_ptr<Backend> CreateVulkanBackend();

// the window changed size: Present follows the surface with a new swapchain
inline std::atomic<bool> g_window_resized{false};
// Android: the activity is in the background (no window), and the surface needs making again
inline std::atomic<bool> g_paused{false};
inline std::atomic<bool> g_surface_lost{false};

}  // namespace gpu

#ifdef __ANDROID__
namespace app {
// width over height of the picture on screen, 0 to fill the whole screen (android/native/app_android.cpp)
float DisplayAspect();
}  // namespace app
#endif
