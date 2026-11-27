cmake_minimum_required(VERSION 3.22)

# The root CMakeLists of the repo names its options ${PROJECT_NAME}_*, so this wrapper
# has to be called "gearmulator" as well.
project(gearmulator)

# <repo>/source/Android/app/src/main/cpp  ->  <repo>
get_filename_component(GM_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../.." ABSOLUTE)

# Pixel 6 and newer are all ARMv8.2-a or better. The default NDK baseline is plain ARMv8.0, which has no
# single-instruction atomics (LSE) and makes the emulator's lock/atomic code noticeably slower.
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} -march=armv8.2-a -O3 -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a -O3 -ffunction-sections -fdata-sections")

# Drop unused code and support devices with 16 KB memory pages (Android 15+)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,-z,max-page-size=16384")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Core only: no JUCE, no other synths, no tests
set(gearmulator_BUILD_JUCEPLUGIN       OFF CACHE BOOL "" FORCE)
set(gearmulator_BUILD_JUCEPLUGIN_CLAP  OFF CACHE BOOL "" FORCE)
set(gearmulator_BUILD_INSTALLER        OFF CACHE BOOL "" FORCE)
set(gearmulator_COMPONENT_DSPBRIDGE    OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_OSIRUS           OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_OSTIRUS          OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_VAVRA            OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_XENIA            OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_NODALRED2X       OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_JE8086           OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_88EMU            OFF CACHE BOOL "" FORCE)
set(gearmulator_SYNTH_ELEKTRON         ON  CACHE BOOL "" FORCE)
set(BUILD_TESTING                      OFF CACHE BOOL "" FORCE)

# Android has no librt (its functions live in libc), but the dsp56300 CMake asks for -lrt.
# An empty static library named "rt" satisfies it.
add_library(rt STATIC rt_stub.c)

add_subdirectory(${GM_ROOT} ${CMAKE_BINARY_DIR}/gearmulator EXCLUDE_FROM_ALL)

add_library(mdmm SHARED mdmm_jni.cpp)
target_link_libraries(mdmm PRIVATE mdLib synthLib aaudio android log)
