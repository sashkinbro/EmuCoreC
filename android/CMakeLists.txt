# EmuCoreC: the Android shared library, built on UPSTREAM RPCS3.
#
# NOTE ON LAYOUT (this is the important part):
# The RPCSX lineage used to make android/ the top-level CMake project and do
# `add_subdirectory(..)` to pull the emulator in. That does not work against
# upstream, because upstream assumes CMAKE_SOURCE_DIR == repo root in at least
# four places -- buildfiles/cmake/FindWolfSSL.cmake, buildfiles/cmake/FindZLIB.cmake,
# 3rdparty/protobuf/CMakeLists.txt and 3rdparty/llvm/CMakeLists.txt all build
# paths as ${CMAKE_SOURCE_DIR}/3rdparty/... . Configuring from android/ makes
# every one of those resolve to android/3rdparty/... and fail.
#
# So here the repo ROOT stays the top-level project and this is an ordinary
# subdirectory, added from the root CMakeLists behind `if(ANDROID)`. Build
# option overrides are passed on the cmake command line (-D...) rather than
# set() here, since they must be visible before add_subdirectory(3rdparty).
#
# Upstream's core target is `rpcs3_emu`. Upstream's rpcs3/CMakeLists.txt already
# guards Qt / rpcs3qt / rpcs3_lib / the rpcs3 executable behind `if (NOT ANDROID)`
# while calling `add_subdirectory(Emu)` unconditionally, so a core-only Android
# configuration is something upstream half-supports already.

# rpcs3/Input/ is not a library upstream -- its sources are compiled straight
# into the `rpcs3` executable, which lives inside `if (NOT ANDROID)`. So on
# Android nothing builds them, yet rpcs3_emu references pad_thread and
# ps_move_tracker (cellPad's LDD pad API, cellGem's tracker), and the link fails
# with undefined symbols. Build the platform-safe subset here.
#
# Deliberately EXCLUDED, and why:
#   evdev_/xinput_/mm_/sdl_*      - Linux/Windows/SDL backends, all disabled
#   basic_keyboard_/basic_mouse_/
#   keyboard_pad_/raw_mouse_/
#   gui_pad_thread/mouse_gyro_    - Qt-dependent (rpcs3qt is not built here)
#   camera_video_sink             - Qt multimedia
set(EMUCOREC_INPUT_SOURCES
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/pad_thread.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/product_info.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/hid_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ds3_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ds4_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/dualsense_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/skateboard_pad_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_handler.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_config.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_calibration.cpp
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/ps_move_tracker.cpp
    # pad_thread references mouse_gyro_handler directly, so it is not optional
    # despite the "mouse" name. Upstream's implementation is Qt-only; EmuCoreC
    # provides a Qt-free one under android/Input/.
    ${CMAKE_SOURCE_DIR}/android/Input/mouse_gyro_handler.cpp
    # Ours: on-screen touch controls.
    ${CMAKE_SOURCE_DIR}/rpcs3/Input/virtual_pad_handler.cpp
)

add_library(emucorec-core SHARED
    src/rpcsx-android.cpp
    # Android OpenGL ES context ownership. This is compiled independently of
    # the renderer while the desktop GL backend is being ported to GLES.
    ${CMAKE_SOURCE_DIR}/rpcs3/Emu/RSX/GL/EGLAndroid.cpp
    ${EMUCOREC_INPUT_SOURCES}
    # rpcs3::get_version() and friends. Like Input/, this is compiled into the
    # `rpcs3` executable upstream, not into rpcs3_emu, so Android never gets it.
    ${CMAKE_SOURCE_DIR}/rpcs3/rpcs3_version.cpp
)

# Ship as libemucorec-core.so, which is the name the app loads
# (see Ps3Runtime). Setting OUTPUT_NAME (rather than renaming the file after
# the fact) also fixes the SONAME, so the file name and the name the dynamic
# linker registers it under agree -- a mismatch there works by luck for a
# plain dlopen and stops working the moment anything resolves it by soname.
set_target_properties(emucorec-core PROPERTIES OUTPUT_NAME emucorec-core)

target_compile_features(emucorec-core PRIVATE cxx_std_23)

target_include_directories(emucorec-core PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/rpcs3
    # rpcsx-android.cpp pulls in Input/hid_pad_handler.h (DS3/DS4/DualSense
    # over USB) and hidapi_libusb.h/libusb.h directly. rpcs3_emu links these
    # PRIVATE, so their include dirs do not propagate to us.
    ${CMAKE_SOURCE_DIR}/3rdparty/hidapi/hidapi/hidapi
    ${CMAKE_SOURCE_DIR}/3rdparty/hidapi/hidapi/libusb
    ${CMAKE_SOURCE_DIR}/3rdparty/libusb/libusb/libusb
    # Utilities/bin_patch.h (patch_engine) includes util/yaml.hpp, which includes
    # yaml-cpp. rpcs3_emu links yaml-cpp PRIVATE, so it does not propagate here.
    ${CMAKE_SOURCE_DIR}/3rdparty/yaml-cpp/yaml-cpp/include
)

target_link_libraries(emucorec-core
    rpcs3_emu
    # ps_move_tracker/ps_move_calibration use the Fusion AHRS solver. rpcs3_lib
    # links this upstream; we build those Input sources ourselves, so we need it.
    3rdparty::fusion
    # The HID pad handlers (DS3/DS4/DualSense/skateboard/PS Move) are ours to
    # build here, so hidapi + libusb are ours to link too.
    3rdparty::hidapi
    3rdparty::libusb
    android
    EGL
    GLESv3
    log
)

# ---------------------------------------------------------------------------
# Profile-guided optimisation
# ---------------------------------------------------------------------------
#
# Scoped to rpcs3_emu and emucorec-core deliberately, NOT to the whole build.
# 3rdparty is mostly LLVM, which is only hot while compiling guest code; adding
# instrumentation to it would multiply an already 1.2GB unstripped artifact for
# a payoff in compile speed rather than in frame time. What we measured as hot
# lives in these two: a simpleperf profile of the RSX thread during gameplay put
# 24.9% in vk::query_pool_manager::get_query_result, 9.4% in
# FIFO_control::fetch_u32_refill and the rest across the VK backend and the FIFO
# dispatch, all of which are rpcs3_emu.
#
#   -DEMUCOREC_PGO=generate                       instrumented build, writes .profraw
#   -DEMUCOREC_PGO=use -DEMUCOREC_PGO_PROFILE=<abs> optimised build against a profile
#
# Configure these into a SEPARATE build directory (BUILD_DIR=... android/configure.sh)
# so the instrumented objects never mix with the normal ones.
set(EMUCOREC_PGO "off" CACHE STRING "Profile-guided optimisation: off, generate, or use")
set(EMUCOREC_PGO_PROFILE "" CACHE FILEPATH "Merged .profdata, required when EMUCOREC_PGO=use")

if(EMUCOREC_PGO STREQUAL "generate")
    # -fprofile-generate on BOTH compile and link: the link is what pulls in the
    # profile runtime that writes the file.
    foreach(pgo_target rpcs3_emu emucorec-core)
        target_compile_options(${pgo_target} PRIVATE -fprofile-generate)
        target_link_options(${pgo_target} PRIVATE -fprofile-generate)
    endforeach()

    # Tells src/rpcsx-android.cpp to name the profile somewhere the app can
    # actually write, and to flush it when the app goes to the background.
    # Without that the runtime writes to the process CWD, which on Android is /
    # and is not writable, so a whole play session produces nothing.
    target_compile_definitions(emucorec-core PRIVATE EMUCOREC_PGO_GENERATE=1)

    message(STATUS "EmuCoreC: PGO instrumentation ON for rpcs3_emu and emucorec-core")
elseif(EMUCOREC_PGO STREQUAL "use")
    if(NOT EMUCOREC_PGO_PROFILE)
        message(FATAL_ERROR "EMUCOREC_PGO=use requires -DEMUCOREC_PGO_PROFILE=<absolute .profdata>")
    endif()
    if(NOT EXISTS "${EMUCOREC_PGO_PROFILE}")
        message(FATAL_ERROR "EMUCOREC_PGO_PROFILE does not exist: ${EMUCOREC_PGO_PROFILE}")
    endif()

    foreach(pgo_target rpcs3_emu emucorec-core)
        # Drift is tolerated rather than fatal: the profile ages the moment the
        # code changes, and a warning per stale function would otherwise turn
        # into thousands of errors under -Werror. Stale enough to matter shows up
        # as a regression, which is why the profile gets regenerated rather than
        # carried forward. A stale profile has actively pessimised this codebase's
        # sibling project before, so treat age as a real risk, not a formality.
        target_compile_options(${pgo_target} PRIVATE
            -fprofile-use=${EMUCOREC_PGO_PROFILE}
            -Wno-error=profile-instr-out-of-date
            -Wno-error=profile-instr-unprofiled
            -Wno-profile-instr-out-of-date
            -Wno-profile-instr-unprofiled)
    endforeach()

    message(STATUS "EmuCoreC: PGO optimising against ${EMUCOREC_PGO_PROFILE}")
endif()

# Discord Social SDK is an optional local build input used by the isolated
# helper process; without it the Kotlin side reports the SDK as unavailable.
if(NOT DISCORD_SDK_DIR AND DEFINED ENV{DISCORD_SDK_DIR})
    set(DISCORD_SDK_DIR "$ENV{DISCORD_SDK_DIR}")
endif()

set(DISCORD_SDK_SO "${DISCORD_SDK_DIR}/${ANDROID_ABI}/libdiscord_partner_sdk.so")
set(DISCORD_SDK_INCLUDE "${DISCORD_SDK_DIR}/include")

if(DISCORD_SDK_DIR AND EXISTS "${DISCORD_SDK_SO}" AND EXISTS "${DISCORD_SDK_INCLUDE}/discordpp.h")
    add_library(discord_partner_sdk SHARED IMPORTED)
    set_target_properties(discord_partner_sdk PROPERTIES
        IMPORTED_LOCATION "${DISCORD_SDK_SO}"
        INTERFACE_INCLUDE_DIRECTORIES "${DISCORD_SDK_INCLUDE}")

    add_library(emucorec_discord SHARED "${CMAKE_SOURCE_DIR}/app/src/main/cpp/discord_bridge.cpp")
    target_link_libraries(emucorec_discord PRIVATE discord_partner_sdk log)
    set_target_properties(emucorec_discord PROPERTIES OUTPUT_NAME "emucorec_discord")
    message(STATUS "Discord Social SDK enabled: ${DISCORD_SDK_SO}")
else()
    message(STATUS "Discord Social SDK unavailable; Discord integration will report unavailable")
endif()

