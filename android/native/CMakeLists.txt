# libmain.so for the Android app: the recompiled game, the SDK runtime and the Android host (this folder).
# Configured by android/build.sh with the NDK toolchain:
#   -DDEPS_PREFIX=<arm64 install of SDL3 and glslang>  -DIMGUI_DIR=<imgui v1.91.0 checkout>
# The recompiled code comes from generated/game (xbe_recomp, run by build.sh from the player's default.xbe).
cmake_minimum_required(VERSION 3.22)
project(PrimalFury LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../..)
set(SDK ${REPO}/sdk)
set(GEN ${REPO}/generated/game)
set(DEPS_PREFIX "" CACHE PATH "arm64 install prefix with SDL3 and glslang")
set(IMGUI_DIR "" CACHE PATH "imgui source checkout")
list(APPEND CMAKE_PREFIX_PATH ${DEPS_PREFIX})
list(APPEND CMAKE_FIND_ROOT_PATH ${DEPS_PREFIX})
find_package(SDL3 REQUIRED CONFIG)
find_package(glslang REQUIRED CONFIG)

# the SDK runtime without its Windows host, SDL input, D3D12 and XAudio2 parts, and the ones this game replaces
file(GLOB RUNTIME_SOURCES ${SDK}/runtime/*/*.cpp)
list(FILTER RUNTIME_SOURCES EXCLUDE REGEX "/runtime/app/|/core/input\\.cpp$|/gpu/d3d12\\.cpp$|/audio/xaudio2\\.cpp$|/xdk/dsound\\.cpp$|/kernel/files\\.cpp$")
# the game's own sources, without its Windows settings, audio and keyboard (android/native has those)
file(GLOB GAME_SOURCES ${REPO}/src/*/*.cpp)
list(FILTER GAME_SOURCES EXCLUDE REGEX "/src/(app|audio|common)/")
file(GLOB HOST_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/*.cpp)
set(IMGUI_SOURCES ${IMGUI_DIR}/imgui.cpp ${IMGUI_DIR}/imgui_draw.cpp ${IMGUI_DIR}/imgui_tables.cpp ${IMGUI_DIR}/imgui_widgets.cpp
    ${IMGUI_DIR}/backends/imgui_impl_vulkan.cpp ${IMGUI_DIR}/backends/imgui_impl_sdl3.cpp)

# imgui 1.91.0's SDL3 backend predates SDL 3.2's renames
set_source_files_properties(${IMGUI_DIR}/backends/imgui_impl_sdl3.cpp PROPERTIES COMPILE_DEFINITIONS SDL_ENABLE_OLD_NAMES)

file(STRINGS ${REPO}/config/game.toml GAME_PARTITIONS REGEX "^partitions *=")
string(REGEX MATCH "[0-9]+" GAME_PARTITIONS "${GAME_PARTITIONS}")
math(EXPR last "${GAME_PARTITIONS} - 1")
set(GEN_SOURCES ${GEN}/x86_func_mapping.cpp)
foreach(i RANGE ${last})
    list(APPEND GEN_SOURCES ${GEN}/x86_recomp.${i}.cpp)
endforeach()
set_source_files_properties(${GEN_SOURCES} PROPERTIES
    COMPILE_OPTIONS "-O2;-fno-strict-aliasing;-w")

# the xbe's SHA-1: the game checks the copy it finds is the release it was recompiled from
file(SHA1 ${REPO}/assets/default.xbe XBE_SHA1)
file(CONFIGURE OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/xbe_hash.cpp CONTENT "extern const char kImageSha1[] = \"${XBE_SHA1}\";\n")

add_library(main SHARED ${RUNTIME_SOURCES} ${GAME_SOURCES} ${HOST_SOURCES} ${IMGUI_SOURCES} ${GEN_SOURCES}
    ${CMAKE_CURRENT_BINARY_DIR}/xbe_hash.cpp)
target_include_directories(main PRIVATE ${REPO}/src ${SDK}/runtime ${GEN} ${SDK}/harness ${SDK}/harness/compat
    ${CMAKE_CURRENT_SOURCE_DIR} ${DEPS_PREFIX}/include)
target_include_directories(main SYSTEM PRIVATE ${IMGUI_DIR})
target_compile_definitions(main PRIVATE ENABLE_HLSL VK_NO_PROTOTYPES IMGUI_IMPL_VULKAN_NO_PROTOTYPES VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(main PRIVATE -fno-strict-aliasing -Wno-unused-label -Wno-parentheses-equality)
target_link_libraries(main PRIVATE SDL3::SDL3 glslang::glslang glslang::SPIRV glslang::glslang-default-resource-limits
    android log dl)
target_link_options(main PRIVATE -Wl,--no-undefined)
