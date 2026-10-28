#include <fstream>
#include "stdafx.h"
#include "save_states.h"
#include "Crypto/unpkg.h"
#include "Crypto/unself.h"
#include "Emu/Audio/Cubeb/CubebBackend.h"
#include "Emu/Audio/Oboe/OboeCapture.h"
#include "Emu/Audio/Null/NullAudioBackend.h"
#include "Emu/Cell/PPUAnalyser.h"
#include "Emu/Cell/SPURecompiler.h"
#include "Emu/Cell/SPUThread.h"
#include "Emu/emu_callbacks.h"
#include "Emu/IdManager.h"
#include "Emu/Io/KeyboardHandler.h"
#include "Emu/Io/Null/NullKeyboardHandler.h"
#include "Emu/Io/Null/NullMouseHandler.h"
#include "Emu/Io/Null/NullPadHandler.h"
#include "Emu/Io/Null/null_camera_handler.h"
#include "Emu/Io/Null/null_music_handler.h"
#include "Emu/Io/pad_config_types.h"
#include "Emu/RSX/Null/NullGSRender.h"
#include "Emu/RSX/Overlays/overlay_manager.h"
#include "Emu/RSX/Overlays/overlay_save_dialog.h"
#include "Emu/RSX/Overlays/overlay_trophy_notification.h"
#include "Emu/RSX/Overlays/overlay_utils.h"
#include "Emu/RSX/RSXThread.h"
#ifdef RSX_GLES
#include "Emu/RSX/GL/GLGSRender.h"
#endif
#include "Emu/RSX/VK/VKGSRender.h"
#include "Emu/savestate_utils.hpp"
// patch_engine, needed by the precompile path before ppu_load_exec.
#include "Utilities/bin_patch.h"
#include "Emu/localized_string_id.h"
#include "Emu/system_config.h"
#include "Emu/system_config_types.h"
#include "Emu/system_progress.hpp"
#include "Emu/system_utils.hpp"
#include "Emu/vfs_config.h"
#include "Emu/VFS.h"
#include "Input/ds3_pad_handler.h"
#include "Input/ds4_pad_handler.h"
#include "Input/dualsense_pad_handler.h"
#include "Input/hid_pad_handler.h"
#include "Input/pad_thread.h"
#include "Input/virtual_pad_handler.h"
#include "Loader/ISO.h"
#include "Loader/PSF.h"
#include "Loader/PUP.h"
#include "Loader/TAR.h"
#include "Emu/Cell/lv2/sys_sync.h"
#include "Emu/Cell/lv2/sys_usbd.h"
#include "hidapi_libusb.h"
#include "libusb.h"
#include "rpcs3_version.h"
#include "Emu/Cell/Modules/cellMsgDialog.h"
#include "Emu/Cell/Modules/cellSysutil.h"
#include "util/asm.hpp"
#include "Utilities/File.h"
#include "Utilities/JIT.h"
#include "Utilities/StrFmt.h"
#include "Utilities/StrUtil.h"
#include "Utilities/Thread.h"
#include "util/console.h"
#include "util/fixed_typemap.hpp"
#include "util/logs.hpp"
#include "util/serialization.hpp"
#include "util/sysinfo.hpp"
#include <Emu/Io/pad_config.h>
#include <Emu/RSX/GSFrameBase.h>
#include <Emu/System.h>
#include <Emu/Cell/Modules/cellSaveData.h>
#include <Emu/Cell/Modules/sceNpTrophy.h>

#include <algorithm>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <filesystem>
#include <functional>
#include <iterator>
#include <jni.h>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <sys/resource.h>
#include <thread>
#include <unordered_set>
#include <vector>

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wreturn-type-c-linkage"

struct AtExit {
  std::function<void()> cb;
  ~AtExit() { cb(); }
};

static bool g_initialized;
static std::atomic<ANativeWindow *> g_native_window;
static std::mutex g_native_window_mutex;

// The surface size Android last reported, packed as (width << 32 | height) so the RSX
// thread reads both halves from one atomic and can never see a width from one orientation
// paired with a height from the next.
//
// SurfaceHolder.Callback::surfaceChanged is the only authoritative source for this. The
// activity handles orientation itself (configChanges in the manifest), so rotating keeps
// the same Surface and the same ANativeWindow -- there is no new handle to notice, and
// ANativeWindow_getWidth on a window a swapchain is already connected to answers about the
// buffers, not the window. Both of those made the renderer keep drawing at the size the
// game booted in.
static std::atomic<u64> g_native_window_size;

// Android's active display refresh rate in millihertz. Keeping an integer
// atomic avoids platform-dependent lock-based atomic<double> implementations.
static std::atomic<u32> g_display_refresh_millihz{60000};

// Set when losing the surface is what paused the emulator, so getting it back resumes only
// the pause we caused.
static std::atomic<bool> g_paused_by_surface_loss;

// Bumped every time g_native_window starts pointing somewhere else. See
// GSFrameBase::display_epoch: a new Surface with the same dimensions as the old one is otherwise
// undetectable by the renderer, and boot-then-immediately-open-a-game is exactly when the
// emulation SurfaceView is replaced under a swapchain that has already been built.
static std::atomic<u64> g_native_window_epoch;
extern std::string g_android_executable_dir;
extern std::string g_android_config_dir;
extern std::string g_android_cache_dir;
// Exact SoC identity as reported by Android (Build.SOC_MANUFACTURER/SOC_MODEL),
// handed in through emucorec_setSocInfo. MIDRs cannot identify the SoC.
static std::string g_android_soc_info;

// Log verbosity floor, set from the app per build variant (-1 = leave the
// upstream defaults). The phone has no log window: the file is evidence for
// bug reports, not a live console, and several upstream channels are periodic
// by design (PERF logs every 10s, VFS/PPU notices per event) which added up to
// megabytes per session. Values match logs::level. The user's explicit
// per-channel levels in the settings tree still win -- see configure_logs().
static std::atomic<int> g_log_level_floor{-1};

extern "C" void emucorec_setLogLevelFloor(int level) {
  g_log_level_floor.store(level, std::memory_order_relaxed);
}

extern "C" int emucorec_log_level_floor() {
  return g_log_level_floor.load(std::memory_order_relaxed);
}
static std::mutex g_virtual_pad_mutex;
// One per PS3 pad port. This was a single pad, which silently capped local
// co-op at one player: every port above the first registered a Pad the app
// could never address, so its input was assembled and then dropped.
static std::array<std::shared_ptr<Pad>, CELL_PAD_MAX_PORT_NUM> g_virtual_pads;

std::string g_input_config_override;
cfg_input_configurations g_cfg_input_configs;

LOG_CHANNEL(emucorec_android, "ANDROID");

#ifdef EMUCOREC_PGO_GENERATE
// PGO instrumentation support. Present only in a -DEMUCOREC_PGO=generate build.
//
// The profile runtime defaults to writing "default.profraw" relative to the
// process working directory, which on Android is / and is not writable, so an
// instrumented build otherwise plays a whole session and produces nothing.
// %p expands to the pid so several runs do not overwrite each other.
extern "C" void __llvm_profile_set_filename(const char*);
extern "C" int __llvm_profile_write_file(void);

static void pgo_set_output_path()
{
    const std::string dir = g_android_cache_dir + "/pgo";
    fs::create_path(dir);

    const std::string pattern = dir + "/emucorec-%p.profraw";
    __llvm_profile_set_filename(pattern.c_str());
    emucorec_android.success("PGO: writing profiles to %s", pattern);
}

// Counters live in memory until the runtime writes them, and that normally
// happens at exit. This process is routinely killed rather than exited (the OOM
// killer took it repeatedly during this work), so waiting for exit loses the
// session. Flushed when the app is backgrounded instead, which is a point the
// user reaches deliberately and which happens before any kill.
static void pgo_flush()
{
    if (__llvm_profile_write_file() == 0)
    {
        emucorec_android.success("PGO: profile written");
    }
    else
    {
        emucorec_android.error("PGO: failed to write profile");
    }
}
#endif

// Severity threshold for the logcat mirror. Defaults to warning: notice/trace still reach
// emucorec.log, they just stop costing a logd round trip each. Lower it at runtime when
// actively debugging on a device.
static std::atomic<int> g_logcat_min_level{static_cast<int>(logs::level::warning)};

struct LogListener : logs::listener {
  LogListener() { logs::listener::add(this); }

  void log(u64 stamp, const logs::message &msg, std::string_view prefix,
           std::string_view text) override {
    int prio = 0;
    switch (static_cast<logs::level>(msg)) {
    case logs::level::always:
      prio = ANDROID_LOG_INFO;
      break;
    case logs::level::fatal:
      prio = ANDROID_LOG_FATAL;
      break;
    case logs::level::error:
      prio = ANDROID_LOG_ERROR;
      break;
    case logs::level::todo:
      prio = ANDROID_LOG_WARN;
      break;
    case logs::level::success:
      prio = ANDROID_LOG_INFO;
      break;
    case logs::level::warning:
      prio = ANDROID_LOG_WARN;
      break;
    case logs::level::notice:
      prio = ANDROID_LOG_DEBUG;
      break;
    case logs::level::trace:
      prio = ANDROID_LOG_VERBOSE;
      break;
    }

    // Mirroring EVERY message to logcat is not free: each one is a heap allocation plus a
    // SYNCHRONOUS IPC round trip to logd. A game that logs thousands of lines a second
    // (LittleBigPlanet 2 does) then spends real frame time inside logd -- measured on device
    // at 5-7 fps, which is why "silence all logs" appears to be a performance setting.
    //
    // It should not have to be. The FILE log is the artifact that gets attached to a bug
    // report; logcat is a live-debugging convenience for whoever is holding the device. So
    // mirror only what that person needs -- warnings and worse -- and let the rest go to the
    // file alone, which is buffered and cheap. Nothing is lost from emucorec.log.
    //
    // Severity is INVERTED in logs::level (always=0 .. trace=7), so '>' drops the noisy end.
    if (static_cast<int>(static_cast<logs::level>(msg)) >
        g_logcat_min_level.load(std::memory_order_relaxed)) {
      return;
    }

    // text is a string_view (upstream changed the listener signature) and string_view is not
    // guaranteed null-terminated, so it cannot go straight to a C API. Reuse a per-thread
    // buffer rather than allocating a fresh std::string for every line.
    thread_local std::string line;
    line.assign(text);
    __android_log_write(prio, "EmuCoreC", line.c_str());
  }
} static g_androidLogListener;

// ---------------------------------------------------------------------------
// Save slot thumbnails.
//
// The most recently captured frame, downscaled, held until a save asks for it.
//
// It has to be cached rather than captured on demand. A save kills the VM, and the point
// where the state is known to be on disk is after_kill_callback, by which time there is no
// renderer left to ask for a picture. So the save requests a screenshot on its way in, this
// catches whatever the renderer produces, and the capture step writes it out afterwards.
//
// Stored small (long edge ~320px) because it is only ever drawn as a tile, and because the
// alternative is parking several megabytes of full-resolution frame per save.
// ---------------------------------------------------------------------------

// Owned by RSXThread.cpp. Setting it makes the next present hand its frame to
// GSFrameBase::take_screenshot.
extern atomic_t<bool> g_user_asked_for_screenshot;

static constexpr u32 kThumbMaxEdge = 320;

static shared_mutex g_thumb_mutex;
static std::vector<u8> g_thumb_rgba; // tightly packed RGBA8
static u32 g_thumb_width = 0;
static u32 g_thumb_height = 0;

static void emucorec_store_thumbnail(const std::vector<u8> &src, u32 width,
                                   u32 height, bool is_bgra) {
  if (src.empty() || width == 0 || height == 0 ||
      src.size() < static_cast<usz>(width) * height * 4) {
    return;
  }

  // Integer step nearest-neighbour. Point sampling is more than enough at tile size, and it
  // reads only the pixels it keeps rather than walking the whole frame.
  const u32 step = std::max<u32>(1, (std::max(width, height) + kThumbMaxEdge - 1) / kThumbMaxEdge);
  const u32 out_w = std::max<u32>(1, width / step);
  const u32 out_h = std::max<u32>(1, height / step);

  std::vector<u8> out(static_cast<usz>(out_w) * out_h * 4);

  for (u32 y = 0; y < out_h; y++) {
    const u8 *row = src.data() + static_cast<usz>(y) * step * width * 4;
    u8 *dst = out.data() + static_cast<usz>(y) * out_w * 4;

    for (u32 x = 0; x < out_w; x++) {
      const u8 *px = row + static_cast<usz>(x) * step * 4;

      // Normalised to RGBA here so the Kotlin side never has to care which backend or
      // surface format produced the frame.
      dst[0] = is_bgra ? px[2] : px[0];
      dst[1] = px[1];
      dst[2] = is_bgra ? px[0] : px[2];
      dst[3] = 0xff; // the frame's alpha is not meaningful for a preview
      dst += 4;
    }
  }

  std::lock_guard lock(g_thumb_mutex);
  g_thumb_rgba = std::move(out);
  g_thumb_width = out_w;
  g_thumb_height = out_h;
}

// "AX3T", u32 width, u32 height, then width*height RGBA8. A private format on purpose:
// encoding a PNG here would mean a compressor in the core for a picture that is decoded
// two centimetres away by code that can build a Bitmap from raw pixels directly.
static bool emucorec_write_thumbnail(const std::string &path) {
  std::lock_guard lock(g_thumb_mutex);

  if (g_thumb_rgba.empty()) {
    return false;
  }

  fs::file out(path, fs::rewrite);

  if (!out) {
    return false;
  }

  const u32 header[2] = {g_thumb_width, g_thumb_height};
  out.write("AX3T", 4);
  out.write(header, sizeof(header));
  out.write(g_thumb_rgba.data(), g_thumb_rgba.size());
  return true;
}

struct GraphicsFrame : GSFrameBase {
  mutable ANativeWindow *activeNativeWindow = nullptr;
  mutable int width = 0;
  mutable int height = 0;

  ~GraphicsFrame() {
    std::lock_guard lock(g_native_window_mutex);
    if (activeNativeWindow != nullptr) {
      ANativeWindow_release(activeNativeWindow);
    }
  }

  ANativeWindow *getNativeWindow() const {
    ANativeWindow *result;

    // Waiting here is normal for a moment at boot -- the RSX thread usually starts before the
    // SurfaceView has been laid out. Waiting here FOREVER is not, and used to be completely
    // silent: surfaceChanged is a one-shot, so a single missed delivery parked this loop for the
    // rest of the session. The symptom was a black game area at 0% CPU with the log stopping dead
    // just after Vulkan device creation and nothing at all to say why. Say so instead.
    u32 waited_ms = 0;

    std::unique_lock window_lock(g_native_window_mutex);
    while ((result = g_native_window.load()) == nullptr) [[unlikely]] {
      if (Emu.IsStopped()) {
        return activeNativeWindow;
      }

      window_lock.unlock();
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
      window_lock.lock();
      waited_ms += 100;

      if (waited_ms % 3000 == 0) {
        emucorec_android.error("Still waiting for a Surface after %u ms -- the renderer cannot start "
                            "until surfaceChanged reaches native.", waited_ms);
      }
    }

    if (result != activeNativeWindow) [[unlikely]] {
      ANativeWindow_acquire(result);

      if (activeNativeWindow != nullptr) {
        ANativeWindow_release(activeNativeWindow);
      }

      activeNativeWindow = result;
    }

    // Fallback size only, for the window we have never been told a size for. The real
    // one arrives through surfaceChanged; see client_width().
    width = ANativeWindow_getWidth(result);
    height = ANativeWindow_getHeight(result);

    return result;
  }

  void close() override {}
  void reset() override {}
  bool shown() override { return true; }
  void hide() override {}
  void show() override {}
  void toggle_fullscreen() override {}

  // The Vulkan backend never touches these - it takes handle() and builds its
  // own surface and swapchain. The OpenGL backend does the opposite: it expects
  // the frame to own the context entirely (GLGSRender::on_init_thread calls
  // make_context, then set_current, and GLPresent calls flip). So all four are
  // real on the GL path and inert on the VK one.
  //
  // Sharing matters here. GLGSRender asks for one extra context per async
  // shader-compiler thread and every one of them has to share objects with the
  // primary, so the first context created is remembered and handed to
  // gl::es::create_context as the share group for all the rest.
  mutable void *primaryGlContext = nullptr;

  // RSX_GLES is a COMPILE-time flag: it says this build contains the GL backend,
  // not that the GL backend is the one running. Gating on it alone made every
  // hook below fire on Vulkan boots too, because GSRender::on_init_thread calls
  // make_context()/set_current() for whichever renderer is active.
  //
  // That was not harmless. set_current() runs eglCreateWindowSurface on the same
  // ANativeWindow the Vulkan swapchain already owns, and a native window can only
  // be connected to one consumer at a time -- so every Vulkan boot logged a fatal
  // "eglCreateWindowSurface failed (0x3003)" (EGL_BAD_ALLOC) and left a stray GLES
  // context behind.
  static bool usingGlRenderer() {
    return g_cfg.video.renderer == video_renderer::opengl;
  }

  void delete_context(draw_context_t ctx) override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return;
    }

    if (ctx == primaryGlContext) {
      primaryGlContext = nullptr;
    }

    gl::es::destroy_context(ctx);
#endif
  }

  draw_context_t make_context() override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return nullptr;
    }

    void *ctx = gl::es::create_context(getNativeWindow(), primaryGlContext);
    if (ctx != nullptr && primaryGlContext == nullptr) {
      primaryGlContext = ctx;
    }

    return ctx;
#else
    return nullptr;
#endif
  }

  void set_current(draw_context_t ctx) override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return;
    }

    gl::es::make_current(ctx, getNativeWindow());
#endif
  }

  void flip(draw_context_t ctx, bool skip_frame = false) override {
#ifdef RSX_GLES
    if (!usingGlRenderer()) {
      return;
    }

    if (!skip_frame) {
      gl::es::swap_buffers();
    }
#endif
  }
  // These two drive the resize check in VKGSRender::flip, so a stale value here does not
  // make the picture late, it makes it wrong: the swapchain keeps the extent it had and
  // the present framebuffer is built to match, whatever shape the window is now.
  //
  // Take the size Android reported for the surface. Falling back to the window is for the
  // window that has arrived but not yet been measured, which only happens before the first
  // surfaceChanged.
  int client_width() override {
    if (!g_native_window.load()) return 0;
    if (const u64 packed = g_native_window_size.load()) {
      return static_cast<int>(packed >> 32);
    }

    getNativeWindow();
    return width;
  }
  int client_height() override {
    if (!g_native_window.load()) return 0;
    if (const u64 packed = g_native_window_size.load()) {
      return static_cast<int>(packed & 0xffffffffu);
    }

    getNativeWindow();
    return height;
  }
  f64 client_display_rate() override {
    return static_cast<f64>(g_display_refresh_millihz.load()) / 1000.0;
  }
  bool has_alpha() override {
    return ANativeWindow_getFormat(getNativeWindow()) ==
           WINDOW_FORMAT_RGBA_8888;
  }

  display_handle_t handle() const override { return getNativeWindow(); }
  u64 surface_generation() const override { return g_native_window_epoch.load(); }


  bool can_consume_frame() const override { return false; }

  // Upstream takes the buffer by rvalue ref (it moves it); the old bridge's
  // lvalue-ref signature no longer overrides, which left GraphicsFrame abstract.
  void present_frame(std::vector<u8> &&data, u32 pitch, u32 width, u32 height,
                     bool is_bgra) const override {}
  // Save slot thumbnails. RPCS3 already renders and hands over the finished frame here
  // whenever a screenshot is requested; this used to drop it, which is why the slot picker
  // had nothing to show. Kept as the most recent frame rather than written straight out:
  // the save that wants it happens later, after the VM has been killed, and there is no
  // frame to ask for by then.
  void take_screenshot(std::vector<u8> &&sshot_data, u32 sshot_width,
                       u32 sshot_height, bool is_bgra) override {
    emucorec_store_thumbnail(sshot_data, sshot_width, sshot_height, is_bgra);
  }
  // Added upstream after this fork diverged. The Android UI draws its own FPS via
  // the perf overlay, so there is no host window title to update.
  void update_title(double fps = 0.0) override {}
};

void jit_announce(uptr, usz, std::string_view);

[[noreturn]] void report_fatal_error(std::string_view _text,
                                     bool is_html = false,
                                     bool include_help_text = true) {
  std::string buf;

  buf = std::string(_text);

  // Check if thread id is in string
  if (_text.find("\nThread id = "sv) == umax && !thread_ctrl::is_main()) {
    // Append thread id if it isn't already, except on main thread
    fmt::append(buf, "\n\nThread id = %u.", thread_ctrl::get_tid());
  }

  if (!g_tls_serialize_name.empty()) {
    fmt::append(buf, "\nSerialized Object: %s", g_tls_serialize_name);
  }

  const system_state state = Emu.GetStatus(false);

  if (state == system_state::stopped) {
    fmt::append(buf, "\nEmulation is stopped");
  } else {
    const std::string &name = Emu.GetTitleAndTitleID();
    fmt::append(buf, "\nTitle: \"%s\" (emulation is %s)",
                name.empty() ? "N/A" : name.data(),
                state == system_state::stopping ? "stopping" : "running");
  }

  fmt::append(buf, "\nBuild: \"%s\"", rpcs3::get_verbose_version());
  fmt::append(buf, "\nDate: \"%s\"", std::chrono::system_clock::now());

  __android_log_write(ANDROID_LOG_FATAL, "RPCS3", buf.c_str());

  jit_announce(0, 0, "");
  utils::trap();
  std::abort();
  std::terminate();
}

void qt_events_aware_op(int repeat_duration_ms,
                        std::function<bool()> wrapped_op) {
  /// ?????
}

static std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}
static jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}
static jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

static std::string fix_dir_path(std::string string) {
  if (!string.empty() && !string.ends_with('/')) {
    string += '/';
  }

  return string;
}

enum class FileType {
  Unknown,
  Pup,
  Pkg,
  Edat,
  Rap,
  Iso,
};

static FileType getFileType(const fs::file &file) {
  file.seek(0);
  if (PUPHeader pupHeader; file.read(pupHeader)) {
    if (pupHeader.magic == "SCEUF\0\0\0"_u64) {
      return FileType::Pup;
    }
  }

  file.seek(0);
  if (PKGHeader pkgHeader; file.read(pkgHeader)) {
    if (pkgHeader.pkg_magic == std::bit_cast<le_t<u32>>("\x7FPKG"_u32)) {
      return FileType::Pkg;
    }
  }

  file.seek(0);
  if (NPD_HEADER npdHeader; file.read(npdHeader)) {
    if (npdHeader.magic == "NPD\0"_u32) {
      return FileType::Edat;
    }
  }

  if (file.size() == 16) {
    return FileType::Rap;
  }

  // EmuCoreC: modern RPCS3 has no dev/iso; detect ISO9660 by its signature.
  file.seek(0x8001);
  std::array<char, 5> iso_sig{};
  if (file.read(iso_sig) && iso_sig == std::array<char, 5>{'C', 'D', '0', '0', '1'}) {
    return FileType::Iso;
  }
  file.seek(0);

  return FileType::Unknown;
}

#define MAKE_STRING(id, x) [int(localized_string_id::id)] = {x, U##x}

static std::pair<std::string, std::u32string> g_strings[] = {
    MAKE_STRING(INVALID, "Invalid"),
    MAKE_STRING(RSX_OVERLAYS_SPINNER_NO_TEXT, ""),
    MAKE_STRING(RSX_OVERLAYS_TROPHY_BRONZE,
                "You have earned a bronze trophy.\n%0"),
    MAKE_STRING(RSX_OVERLAYS_TROPHY_SILVER,
                "You have earned a silver trophy.\n%0"),
    MAKE_STRING(RSX_OVERLAYS_TROPHY_GOLD, "You have earned a gold trophy.\n%0"),
    MAKE_STRING(RSX_OVERLAYS_TROPHY_PLATINUM,
                "You have earned a platinum trophy.\n%0"),
    MAKE_STRING(RSX_OVERLAYS_COMPILING_SHADERS, "Compiling shaders"),
    MAKE_STRING(RSX_OVERLAYS_COMPILING_PPU_MODULES, "Compiling PPU Modules"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_YES, "Yes"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_NO, "No"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_CANCEL, "Back"),
    MAKE_STRING(RSX_OVERLAYS_MSG_DIALOG_OK, "OK"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_TITLE, "Save Dialog"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_DELETE, "Delete Save"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_LOAD, "Load Save"),
    MAKE_STRING(RSX_OVERLAYS_SAVE_DIALOG_SAVE, "Save"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_ACCEPT, "Enter"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_CANCEL, "Back"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_SPACE, "Space"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_BACKSPACE, "Backspace"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_SHIFT, "Shift"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_ENTER_TEXT, "[Enter Text]"),
    MAKE_STRING(RSX_OVERLAYS_OSK_DIALOG_ENTER_PASSWORD, "[Enter Password]"),
    MAKE_STRING(RSX_OVERLAYS_MEDIA_DIALOG_TITLE, "Select media"),
    MAKE_STRING(RSX_OVERLAYS_MEDIA_DIALOG_TITLE_PHOTO_IMPORT,
                "Select photo to import"),
    MAKE_STRING(RSX_OVERLAYS_MEDIA_DIALOG_EMPTY, "No media found."),
    MAKE_STRING(RSX_OVERLAYS_LIST_SELECT, "Enter"),
    MAKE_STRING(RSX_OVERLAYS_LIST_CANCEL, "Back"),
    MAKE_STRING(RSX_OVERLAYS_LIST_DENY, "Deny"),
    MAKE_STRING(RSX_OVERLAYS_PRESSURE_INTENSITY_TOGGLED_OFF,
                "Pressure intensity mode of player %0 disabled"),
    MAKE_STRING(RSX_OVERLAYS_PRESSURE_INTENSITY_TOGGLED_ON,
                "Pressure intensity mode of player %0 enabled"),
    MAKE_STRING(RSX_OVERLAYS_ANALOG_LIMITER_TOGGLED_OFF,
                "Analog limiter of player %0 disabled"),
    MAKE_STRING(RSX_OVERLAYS_ANALOG_LIMITER_TOGGLED_ON,
                "Analog limiter of player %0 enabled"),
    MAKE_STRING(RSX_OVERLAYS_MOUSE_AND_KEYBOARD_EMULATED,
                "Mouse and keyboard are now used as emulated devices."),
    MAKE_STRING(RSX_OVERLAYS_MOUSE_AND_KEYBOARD_PAD,
                "Mouse and keyboard are now used as pad."),
    MAKE_STRING(
        CELL_GAME_ERROR_BROKEN_GAMEDATA,
        "ERROR: Game data is corrupted. The application will continue."),
    MAKE_STRING(
        CELL_GAME_ERROR_BROKEN_HDDGAME,
        "ERROR: HDD boot game is corrupted. The application will continue."),
    MAKE_STRING(
        CELL_GAME_ERROR_BROKEN_EXIT_GAMEDATA,
        "ERROR: Game data is corrupted. The application will be terminated."),
    MAKE_STRING(CELL_GAME_ERROR_BROKEN_EXIT_HDDGAME,
                "ERROR: HDD boot game is corrupted. The application will be "
                "terminated."),
    MAKE_STRING(CELL_GAME_ERROR_NOSPACE,
                "ERROR: Not enough available space. The application will "
                "continue.\nSpace needed: %0 KB"),
    MAKE_STRING(CELL_GAME_ERROR_NOSPACE_EXIT,
                "ERROR: Not enough available space. The application will be "
                "terminated.\nSpace needed: %0 KB"),
    MAKE_STRING(CELL_GAME_ERROR_DIR_NAME, "Directory name: %0"),
    MAKE_STRING(CELL_GAME_DATA_EXIT_BROKEN,
                "There has been an error!\n\nPlease remove the game data for "
                "this title."),
    MAKE_STRING(
        CELL_HDD_GAME_EXIT_BROKEN,
        "There has been an error!\n\nPlease reinstall the HDD boot game."),
    MAKE_STRING(
        CELL_HDD_GAME_CHECK_NOSPACE,
        "Not enough space to create HDD boot game.\nSpace Needed: %0 KB"),
    MAKE_STRING(CELL_HDD_GAME_CHECK_BROKEN, "HDD boot game %0 is corrupt!"),
    MAKE_STRING(CELL_HDD_GAME_CHECK_NODATA,
                "HDD boot game %0 could not be found!"),
    MAKE_STRING(CELL_HDD_GAME_CHECK_INVALID, "Error: %0"),
    MAKE_STRING(CELL_GAMEDATA_CHECK_NOSPACE,
                "Not enough space to create game data.\nSpace Needed: %0 KB"),
    MAKE_STRING(CELL_GAMEDATA_CHECK_BROKEN, "The game data in %0 is corrupt!"),
    MAKE_STRING(CELL_GAMEDATA_CHECK_NODATA,
                "The game data in %0 could not be found!"),
    MAKE_STRING(CELL_GAMEDATA_CHECK_INVALID, "Error: %0"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_DEFAULT, "An error has occurred.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010001,
                "The resource is temporarily unavailable.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010002,
                "Invalid argument or flag.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010003,
                "The feature is not yet implemented.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010004,
                "Memory allocation failed.\n(%0)"),
    MAKE_STRING(
        CELL_MSG_DIALOG_ERROR_80010005,
        "The resource with the specified identifier does not exist.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010006,
                "The file does not exist.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010007,
                "The file is in an unrecognized format / The file is not a "
                "valid ELF file.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010008,
                "Resource deadlock is avoided.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010009,
                "Operation not permitted.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001000A,
                "The device or resource is busy.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001000B,
                "The operation is timed out.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001000C,
                "The operation is aborted.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001000D, "Invalid memory access.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001000F,
                "State of the target thread is invalid.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010010, "Alignment is invalid.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010011,
                "Shortage of the kernel resources.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010012,
                "The file is a directory.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010013, "Operation cancelled.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010014, "Entry already exists.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010015,
                "Port is already connected.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010016, "Port is not connected.\n(%0)"),
    MAKE_STRING(
        CELL_MSG_DIALOG_ERROR_80010017,
        "Failure in authorizing SELF. Program authentication fail.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010018, "The file is not MSELF.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010019, "System version error.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001001A,
                "Fatal system error occurred while authorizing SELF. SELF auth "
                "failure.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001001B, "Math domain violation.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001001C, "Math range violation.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001001D,
                "Illegal multi-byte sequence in input.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001001E, "File position error.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001001F,
                "Syscall was interrupted.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010020, "File too large.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010021, "Too many links.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010022, "File table overflow.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010023,
                "No space left on device.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010024, "Not a TTY.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010025, "Broken pipe.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010026, "Read-only filesystem.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010027, "Illegal seek.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010028, "Arg list too long.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010029, "Access violation.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001002A,
                "Invalid file descriptor.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001002B,
                "Filesystem mounting failed.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001002C, "Too many files open.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001002D, "No device.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001002E, "Not a directory.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001002F, "No such device or IO.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010030,
                "Cross-device link error.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010031, "Bad Message.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010032, "In progress.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010033, "Message size error.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010034, "Name too long.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010035, "No lock.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010036, "Not empty.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010037, "Not supported.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010038,
                "File-system specific error.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_80010039, "Overflow occurred.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001003A,
                "Filesystem not mounted.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001003B, "Not SData.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001003C,
                "Incorrect version in sys_load_param.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001003D, "Pointer is null.\n(%0)"),
    MAKE_STRING(CELL_MSG_DIALOG_ERROR_8001003E, "Pointer is null.\n(%0)"),
    MAKE_STRING(CELL_OSK_DIALOG_TITLE, "On Screen Keyboard"),
    MAKE_STRING(
        CELL_OSK_DIALOG_BUSY,
        "The Home Menu can't be opened while the On Screen Keyboard is busy!"),
    MAKE_STRING(CELL_SAVEDATA_CB_BROKEN, "Error - Save data corrupted"),
    MAKE_STRING(CELL_SAVEDATA_CB_FAILURE, "Error - Failed to save or load"),
    MAKE_STRING(CELL_SAVEDATA_CB_NO_DATA, "Error - Save data cannot be found"),
    MAKE_STRING(CELL_SAVEDATA_CB_NO_SPACE,
                "Error - Insufficient free space\n\nSpace needed: %0 KB"),
    MAKE_STRING(CELL_SAVEDATA_NO_DATA, "There is no saved data."),
    MAKE_STRING(CELL_SAVEDATA_NEW_SAVED_DATA_TITLE, "New Saved Data"),
    MAKE_STRING(CELL_SAVEDATA_NEW_SAVED_DATA_SUB_TITLE,
                "Select to create a new entry"),
    MAKE_STRING(CELL_SAVEDATA_SAVE_CONFIRMATION,
                "Do you want to save this data?"),
    MAKE_STRING(CELL_SAVEDATA_DELETE_CONFIRMATION,
                "Do you really want to delete this data?\n\n%0"),
    MAKE_STRING(CELL_SAVEDATA_DELETE_SUCCESS,
                "Successfully removed data!\n\n%0"),
    MAKE_STRING(CELL_SAVEDATA_DELETE, "Delete this data?\n\n%0"),
    MAKE_STRING(CELL_SAVEDATA_LOAD, "Load this data?\n\n%0"),
    MAKE_STRING(CELL_SAVEDATA_OVERWRITE,
                "Do you want to overwrite the saved data?\n\n%0"),
    MAKE_STRING(CELL_SAVEDATA_AUTOSAVE, "Saving..."),
    MAKE_STRING(CELL_SAVEDATA_AUTOLOAD, "Loading..."),
    MAKE_STRING(CELL_CROSS_CONTROLLER_MSG,
                "Start [%0] on the PS Vita system.\nIf you have not installed "
                "[%0], go to [Remote Play] on the PS Vita system and start "
                "[Cross-Controller] from the LiveAreaв„ў screen."),
    MAKE_STRING(
        CELL_CROSS_CONTROLLER_FW_MSG,
        "If your system software version on the PS Vita system is earlier than "
        "1.80, you must update the system software to the latest version."),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_TITLE, "Select Message"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_TITLE_INVITE, "Select Invite"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_TITLE_ADD_FRIEND, "Add Friend"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_FROM, "From:"),
    MAKE_STRING(CELL_NP_RECVMESSAGE_DIALOG_SUBJECT, "Subject:"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_TITLE, "Select Message To Send"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_TITLE_INVITE, "Send Invite"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_TITLE_ADD_FRIEND, "Add Friend"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_CONFIRMATION,
                "Send message to %0 ?\n\nSubject:"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_CONFIRMATION_INVITE,
                "Send invite to %0 ?\n\nSubject:"),
    MAKE_STRING(CELL_NP_SENDMESSAGE_DIALOG_CONFIRMATION_ADD_FRIEND,
                "Send friend request to %0 ?\n\nSubject:"),
    MAKE_STRING(CELL_NP_MESSAGE_INVITE_RECEIVED, "Received an invite from %0"),
    MAKE_STRING(CELL_NP_MESSAGE_OTHER_RECEIVED, "Received a message from %0"),
    MAKE_STRING(RECORDING_ABORTED, "Recording aborted!"),
    MAKE_STRING(RPCN_NO_ERROR, "RPCN: No Error"),
    MAKE_STRING(RPCN_ERROR_INVALID_INPUT,
                "RPCN: Invalid Input (Wrong Host/Port)"),
    MAKE_STRING(RPCN_ERROR_WOLFSSL, "RPCN Connection Error: WolfSSL Error"),
    MAKE_STRING(RPCN_ERROR_RESOLVE, "RPCN Connection Error: Resolve Error"),
    MAKE_STRING(RPCN_ERROR_BINDING,
                "RPCN Connection Error: Failed to bind to given binding IP"),
    MAKE_STRING(RPCN_ERROR_CONNECT, "RPCN Connection Error"),
    MAKE_STRING(RPCN_ERROR_LOGIN_ERROR,
                "RPCN Login Error: Identification Error"),
    MAKE_STRING(RPCN_ERROR_ALREADY_LOGGED,
                "RPCN Login Error: User Already Logged In"),
    MAKE_STRING(RPCN_ERROR_INVALID_LOGIN, "RPCN Login Error: Invalid Username"),
    MAKE_STRING(RPCN_ERROR_INVALID_PASSWORD,
                "RPCN Login Error: Invalid Password"),
    MAKE_STRING(RPCN_ERROR_INVALID_TOKEN, "RPCN Login Error: Invalid Token"),
    MAKE_STRING(RPCN_ERROR_INVALID_PROTOCOL_VERSION,
                "RPCN Misc Error: Protocol Version Error (outdated RPCS3?)"),
    MAKE_STRING(RPCN_ERROR_UNKNOWN, "RPCN: Unknown Error"),
    MAKE_STRING(RPCN_SUCCESS_LOGGED_ON, "Successfully logged on RPCN!"),
    MAKE_STRING(RPCN_FRIEND_REQUEST_RECEIVED,
                "RPCN: Received friend request: %0"),
    MAKE_STRING(RPCN_FRIEND_ADDED, "RPCN: Friend added: %0"),
    MAKE_STRING(RPCN_FRIEND_LOST, "RPCN: Friend removed: %0"),
    MAKE_STRING(RPCN_FRIEND_LOGGED_IN, "RPCN: %0 logged in"),
    MAKE_STRING(RPCN_FRIEND_LOGGED_OUT, "RPCN: %0 logged out"),
    MAKE_STRING(HOME_MENU_TITLE, "Home Menu"),
    MAKE_STRING(HOME_MENU_EXIT_GAME, "Exit Game"),
    MAKE_STRING(HOME_MENU_RESTART, "Restart Game"),
    MAKE_STRING(HOME_MENU_RESUME, "Resume Game"),
    MAKE_STRING(HOME_MENU_FRIENDS, "Friends"),
    MAKE_STRING(HOME_MENU_FRIENDS_REQUESTS, "Pending Friend Requests"),
    MAKE_STRING(HOME_MENU_FRIENDS_GAME_INVITES, "Game Invitations"),
    MAKE_STRING(HOME_MENU_FRIENDS_BLOCKED, "Blocked Users"),
    MAKE_STRING(HOME_MENU_FRIENDS_STATUS_ONLINE, "Online"),
    MAKE_STRING(HOME_MENU_FRIENDS_STATUS_OFFLINE, "Offline"),
    MAKE_STRING(HOME_MENU_FRIENDS_STATUS_BLOCKED, "Blocked"),
    MAKE_STRING(HOME_MENU_FRIENDS_REQUEST_SENT, "You sent a friend request"),
    MAKE_STRING(HOME_MENU_FRIENDS_REQUEST_RECEIVED,
                "Sent you a friend request"),
    MAKE_STRING(HOME_MENU_FRIENDS_BLOCK_USER_MSG, "Block this user?\n\n%0"),
    MAKE_STRING(HOME_MENU_FRIENDS_UNBLOCK_USER_MSG, "Unblock this user?\n\n%0"),
    MAKE_STRING(HOME_MENU_FRIENDS_REMOVE_USER_MSG, "Remove this user?\n\n%0"),
    MAKE_STRING(HOME_MENU_FRIENDS_ACCEPT_REQUEST_MSG, "Accept Request?\n\n%0"),
    MAKE_STRING(HOME_MENU_FRIENDS_CANCEL_REQUEST_MSG, "Cancel Request?\n\n%0"),
    MAKE_STRING(HOME_MENU_FRIENDS_REJECT_REQUEST_MSG, "Reject Request?\n\n%0"),
    MAKE_STRING(HOME_MENU_FRIENDS_REJECT_REQUEST, "Reject Request"),
    MAKE_STRING(HOME_MENU_FRIENDS_ACCEPT_GAME_INVITE_MSG,
                "Accept game invitation from %0?"),
    MAKE_STRING(HOME_MENU_FRIENDS_REJECT_GAME_INVITE_MSG,
                "Reject game invitation from %0?"),
    MAKE_STRING(HOME_MENU_FRIENDS_REJECT_GAME_INVITE, "Reject Invitation"),
    MAKE_STRING(HOME_MENU_FRIENDS_NEXT_LIST, "Next list"),
    MAKE_STRING(HOME_MENU_SETTINGS, "Settings"),
    MAKE_STRING(HOME_MENU_SETTINGS_SAVE, "Save custom configuration?"),
    MAKE_STRING(HOME_MENU_SETTINGS_SAVE_BUTTON, "Save"),
    MAKE_STRING(HOME_MENU_SETTINGS_DISCARD,
                "Discard the current settings' changes?"),
    MAKE_STRING(HOME_MENU_SETTINGS_DISCARD_BUTTON, "Discard"),
    MAKE_STRING(HOME_MENU_SETTINGS_RESET_BUTTON, "To default"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO, "Audio"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_MASTER_VOLUME, "Master Volume"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_BACKEND, "Audio Backend"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_BUFFERING, "Enable Buffering"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_BUFFER_DURATION,
                "Desired Audio Buffer Duration"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_TIME_STRETCHING,
                "Enable Time Stretching"),
    MAKE_STRING(HOME_MENU_SETTINGS_AUDIO_TIME_STRETCHING_THRESHOLD,
                "Time Stretching Threshold"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO, "Video"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_VSYNC, "VSync"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_FRAME_LIMIT, "Frame Limit"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_ANISOTROPIC_OVERRIDE,
                "Anisotropic Filter Override"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_OUTPUT_SCALING, "Output Scaling"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_RCAS_SHARPENING,
                "FidelityFX CAS Sharpening Intensity"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_RESOLUTION_SCALE_PERCENT,
                "Resolution Scale"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_RESOLUTION_SCALE_THRESHOLD,
                "Resolution Scale Threshold"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_STRETCH_TO_DISPLAY,
                "Stretch To Display Area"),
    MAKE_STRING(HOME_MENU_SETTINGS_VIDEO_STEREO_MODE, "Stereo Mode"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT, "Input"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_BACKGROUND_INPUT,
                "Background Input Enabled"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_KEEP_PADS_CONNECTED,
                "Keep Pads Connected"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_SHOW_PS_MOVE_CURSOR,
                "Show PS Move Cursor"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_CAMERA_FLIP, "Camera Flip"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_PAD_MODE, "Pad Handler Mode"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_PAD_SLEEP, "Pad Handler Sleep"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_FAKE_MOVE_ROTATION_CONE_H,
                "Fake PS Move Rotation Cone (Horizontal)"),
    MAKE_STRING(HOME_MENU_SETTINGS_INPUT_FAKE_MOVE_ROTATION_CONE_V,
                "Fake PS Move Rotation Cone (Vertical)"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED, "Advanced"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_PREFERRED_SPU_THREADS,
                "Preferred SPU Threads"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_MAX_CPU_PREEMPTIONS,
                "Max Power Saving CPU-Preemptions"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_ACCURATE_RSX_RESERVATION_ACCESS,
                "Accurate RSX reservation access"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_SLEEP_TIMERS_ACCURACY,
                "Sleep Timers Accuracy"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_RSX_MEMORY_TILING,
                "Handle RSX Memory Tiling"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_MAX_SPURS_THREADS,
                "Max SPURS Threads"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_DRIVER_WAKE_UP_DELAY,
                "Driver Wake-Up Delay"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_VBLANK_FREQUENCY,
                "VBlank Frequency"),
    MAKE_STRING(HOME_MENU_SETTINGS_ADVANCED_VBLANK_NTSC, "VBlank NTSC Fixup"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS, "Overlays"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_TROPHY_POPUPS,
                "Show Trophy Popups"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_RPCN_POPUPS,
                "Show RPCN Popups"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_SHADER_COMPILATION_HINT,
                "Show Shader Compilation Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_PPU_COMPILATION_HINT,
                "Show PPU Compilation Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_AUTO_SAVE_LOAD_HINT,
                "Show Autosave/Autoload Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_PRESSURE_INTENSITY_TOGGLE_HINT,
                "Show Pressure Intensity Toggle Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_ANALOG_LIMITER_TOGGLE_HINT,
                "Show Analog Limiter Toggle Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_MOUSE_AND_KB_TOGGLE_HINT,
                "Show Mouse And Keyboard Toggle Hint"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_SHOW_FATAL_ERROR_HINTS,
                "Show Fatal Error Hints"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_RECORD_WITH_OVERLAYS,
                "Record With Overlays"),
    MAKE_STRING(HOME_MENU_SETTINGS_OVERLAYS_PLAY_MUSIC_DURING_BOOT,
                "Play music during boot sequence."),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY, "Performance Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_ENABLE,
                "Enable Performance Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_SHOW_HEADER,
                "Show Header"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_ENABLE_FRAMERATE_GRAPH,
                "Enable Framerate Graph"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_ENABLE_FRAMETIME_GRAPH,
                "Enable Frametime Graph"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_DETAIL_LEVEL,
                "Detail level"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMERATE_DETAIL_LEVEL,
                "Framerate Graph Detail Level"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMETIME_DETAIL_LEVEL,
                "Frametime Graph Detail Level"),
    MAKE_STRING(
        HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMERATE_DATAPOINT_COUNT,
        "Framerate Datapoints"),
    MAKE_STRING(
        HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FRAMETIME_DATAPOINT_COUNT,
        "Frametime Datapoints"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_UPDATE_INTERVAL,
                "Metrics Update Interval"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_POSITION, "Position"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_CENTER_X,
                "Center Horizontally"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_CENTER_Y,
                "Center Vertically"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_MARGIN_X,
                "Horizontal Margin"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_MARGIN_Y,
                "Vertical Margin"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_FONT_SIZE, "Font Size"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_OPACITY, "Opacity"),
    MAKE_STRING(HOME_MENU_SETTINGS_PERFORMANCE_OVERLAY_USE_WINDOW_SPACE,
                "Use Window Space"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG, "Debug"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_OVERLAY, "Debug Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_INPUT_OVERLAY, "Input Debug Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_MOUSE_DEBUG_INPUT_OVERLAY,
                "Mouse Debug Overlay"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_DISABLE_VIDEO_OUTPUT,
                "Disable Video Output"),
    MAKE_STRING(HOME_MENU_SETTINGS_DEBUG_TEXTURE_LOD_BIAS,
                "Texture LOD Bias Addend"),
    MAKE_STRING(HOME_MENU_SCREENSHOT, "Take Screenshot"),
    MAKE_STRING(HOME_MENU_SAVESTATE, "SaveState"),
    MAKE_STRING(HOME_MENU_SAVESTATE_SAVE, "Save Emulation State"),
    MAKE_STRING(HOME_MENU_SAVESTATE_AND_EXIT, "Save Emulation State And Exit"),
    MAKE_STRING(HOME_MENU_RELOAD_SAVESTATE, "Reload Last Emulation State"),
    MAKE_STRING(HOME_MENU_RELOAD_SECOND_SAVESTATE,
                "Reload Second-To-Last Emulation State"),
    MAKE_STRING(HOME_MENU_RELOAD_THIRD_SAVESTATE,
                "Reload Third-To-Last Emulation State"),
    MAKE_STRING(HOME_MENU_RELOAD_FOURTH_SAVESTATE,
                "Reload Fourth-To-Last Emulation State"),
    MAKE_STRING(HOME_MENU_TOGGLE_FULLSCREEN, "Toggle Fullscreen"),
    MAKE_STRING(HOME_MENU_RECORDING, "Start/Stop Recording"),
    MAKE_STRING(HOME_MENU_TROPHIES, "Trophies"),
    MAKE_STRING(HOME_MENU_TROPHY_LIST_TITLE, "Trophy Progress: %0"),
    MAKE_STRING(HOME_MENU_TROPHY_LOCKED_TITLE, "Locked trophy: %0"),
    MAKE_STRING(HOME_MENU_TROPHY_HIDDEN_TITLE, "Hidden trophy"),
    MAKE_STRING(HOME_MENU_TROPHY_HIDDEN_DESCRIPTION, "This trophy is hidden"),
    MAKE_STRING(HOME_MENU_TROPHY_SHOW_HIDDEN_TROPHIES, "Show hidden trophies"),
    MAKE_STRING(HOME_MENU_TROPHY_HIDE_HIDDEN_TROPHIES, "Hide hidden trophies"),
    MAKE_STRING(HOME_MENU_TROPHY_PLATINUM_RELEVANT, "Platinum relevant"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_BRONZE, "Bronze"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_SILVER, "Silver"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_GOLD, "Gold"),
    MAKE_STRING(HOME_MENU_TROPHY_GRADE_PLATINUM, "Platinum"),
    MAKE_STRING(HOME_MENU_TROPHY_SORT_GAME_DEFAULT, "Sort: Game Default"),
    MAKE_STRING(HOME_MENU_TROPHY_SORT_NOT_EARNED, "Sort: Not Earned"),
    MAKE_STRING(HOME_MENU_TROPHY_SORT_EARNED_DATE, "Sort: Earned Date"),
    MAKE_STRING(HOME_MENU_TROPHY_SORT_GRADE, "Sort: Grade"),
    MAKE_STRING(AUDIO_MUTED, "Audio muted"),
    MAKE_STRING(AUDIO_UNMUTED, "Audio unmuted"),
    MAKE_STRING(AUDIO_CHANGED, "Volume changed to %0"),
    MAKE_STRING(PROGRESS_DIALOG_PROGRESS, "Progress:"),
    MAKE_STRING(PROGRESS_DIALOG_PROGRESS_ANALYZING, "Progress: analyzing..."),
    MAKE_STRING(PROGRESS_DIALOG_REMAINING, "remaining"),
    MAKE_STRING(PROGRESS_DIALOG_DONE, "done"),
    MAKE_STRING(PROGRESS_DIALOG_FILE, "file"),
    MAKE_STRING(PROGRESS_DIALOG_MODULE, "module"),
    MAKE_STRING(PROGRESS_DIALOG_OF, "of"),
    MAKE_STRING(PROGRESS_DIALOG_PLEASE_WAIT, "Please wait"),
    MAKE_STRING(PROGRESS_DIALOG_STOPPING_PLEASE_WAIT,
                "Stopping. Please wait..."),
    MAKE_STRING(PROGRESS_DIALOG_SAVESTATE_PLEASE_WAIT,
                "Creating savestate. Please wait..."),
    MAKE_STRING(PROGRESS_DIALOG_SCANNING_PPU_EXECUTABLE,
                "Scanning PPU Executable..."),
    MAKE_STRING(PROGRESS_DIALOG_ANALYZING_PPU_EXECUTABLE,
                "Analyzing PPU Executable..."),
    MAKE_STRING(PROGRESS_DIALOG_SCANNING_PPU_MODULES,
                "Scanning PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_LOADING_PPU_MODULES, "Loading PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_COMPILING_PPU_MODULES,
                "Compiling PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_LINKING_PPU_MODULES, "Linking PPU Modules..."),
    MAKE_STRING(PROGRESS_DIALOG_APPLYING_PPU_CODE, "Applying PPU Code..."),
    MAKE_STRING(PROGRESS_DIALOG_BUILDING_SPU_CACHE, "Building SPU Cache..."),
    MAKE_STRING(EMULATION_PAUSED_RESUME_WITH_START,
                "Press and hold the START button to resume"),
    MAKE_STRING(EMULATION_RESUMING, "Resuming...!"),
    MAKE_STRING(EMULATION_FROZEN,
                "The PS3 application has likely crashed, you can close it."),
    MAKE_STRING(SAVESTATE_FAILED_DUE_TO_VDEC,
                "SaveState failed: VDEC-based video/cutscenes are in order, "
                "wait for them to end or enable libvdec.sprx."),
    MAKE_STRING(
        SAVESTATE_FAILED_DUE_TO_SAVEDATA,
        "SaveState failed: Game saving is in progress, wait until finished."),
    MAKE_STRING(SAVESTATE_FAILED_DUE_TO_SPU,
                "SaveState failed: Failed to lock SPU state, using SPU ASMJIT "
                "will fix it."),
    MAKE_STRING(SAVESTATE_FAILED_DUE_TO_MISSING_SPU_SETTING,
                "SaveState failed: Failed to lock SPU state, enabling "
                "SPU-Compatible mode may fix it."),
};

#undef MAKE_STRING

// One index per enum entry, so an id upstream adds is a hole in the middle of
// the table rather than a shift, and both show up here instead of in a blank
// dialog on a phone.
static_assert(
    std::size(g_strings) ==
        std::size_t(
            localized_string_id::SAVESTATE_FAILED_DUE_TO_MISSING_SPU_SETTING) +
            1,
    "localized_string_id grew: add the new string(s) to g_strings and move "
    "this to the new last id");

// The desktop frontend hands the string to QString::arg(), which replaces the
// lowest numbered "%N" place marker wherever it appears. Every string the core
// passes an argument to uses %0 and passes exactly one, so that reduces to
// this. Dropping the argument is why the save-data prompt showed no entry
// title, date or size, and why every cellMsgDialog error arrived without its
// error code.
template <typename T>
static std::basic_string<T> substitute_arg(std::basic_string<T> text,
                                           std::basic_string_view<T> arg) {
  const T marker[] = {T('%'), T('0'), T(0)};

  for (std::size_t pos = text.find(marker); pos != text.npos;
       pos = text.find(marker, pos + arg.size())) {
    text.replace(pos, 2, arg);
  }

  return text;
}

static const std::pair<std::string, std::u32string> *
find_localized_string(localized_string_id id) {
  const std::size_t index = std::size_t(id);

  if (index >= std::size(g_strings)) {
    emucorec_android.error("No localized string for id %d", int(id));
    return nullptr;
  }

  // RSX_OVERLAYS_SPINNER_NO_TEXT is deliberately empty; anything else empty is
  // a hole.
  if (g_strings[index].first.empty() &&
      id != localized_string_id::RSX_OVERLAYS_SPINNER_NO_TEXT) {
    emucorec_android.error("Localized string %d is missing from g_strings",
                        int(id));
    return nullptr;
  }

  return &g_strings[index];
}

class Progress {
  JNIEnv *env;
  jlong progressId;
  jclass progressRepositoryClass;
  jmethodID onProgressEventMethodId;
  jmethodID onProgressCompletedMethodId;

public:
  // Every JNI object handed back to native code is a LOCAL reference, and local
  // references are only reclaimed when the frame that made them returns to Java.
  // The frames this runs on do not return: MainThreadProcessor::process and the
  // compilation queue are infinite loops inside a single JNI call, so anything
  // taken here is held for the life of the process.
  //
  // FindClass and NewStringUTF were both leaking, once per Progress and once per
  // report(). The progress dialog server pushes several updates per tick and a
  // firmware precompile emits thousands of ticks, so ART's local reference table
  // (512 entries) filled and the runtime aborted with "local reference table
  // overflow". Time-proportional, which is why it showed up on slow devices during
  // long installs and not on fast ones.
  Progress(JNIEnv *env, jlong progressId) : env(env), progressId(progressId) {
    progressRepositoryClass =
        ensure(env->FindClass("com/sbro/emucorec/core/Ps3CoreCallbacks"));
    onProgressEventMethodId = env->GetStaticMethodID(
        progressRepositoryClass, "onProgressEvent", "(JJJLjava/lang/String;)Z");
    onProgressCompletedMethodId = ensure(env->GetStaticMethodID(
        progressRepositoryClass, "onProgressCompleted", "(JJJLjava/lang/String;)Z"));
  }

  ~Progress() {
    if (progressRepositoryClass != nullptr) {
      env->DeleteLocalRef(progressRepositoryClass);
    }
  }

  // Owns a local reference, so it cannot be copied: the second destructor would
  // delete a reference the first already released.
  Progress(const Progress &) = delete;
  Progress &operator=(const Progress &) = delete;

  bool report(jlong value, jlong max, const std::string &message = {}) {
    return send(onProgressEventMethodId, value, max, message);
  }

private:
  bool send(jmethodID method, jlong value, jlong max, const std::string &message) {
    jstring text = message.empty() ? nullptr : wrap(env, message);

    const bool result = env->CallStaticBooleanMethod(
        progressRepositoryClass, method, progressId, value,
        max, text);

    if (text != nullptr) {
      env->DeleteLocalRef(text);
    }

    return result;
  }

public:
  void failure(const std::string &message = {}) { report(-1, 0, message); }

  void success(jlong value, const std::string &message = {}) {
    value = std::max<jlong>(value, 1);
    send(onProgressCompletedMethodId, value, value, message);
  }

  jlong getProgressId() const { return progressId; }
};

static void sendFirmwareInstalled(JNIEnv *env, const std::string &version) {
  auto fwRepositoryClass =
      ensure(env->FindClass("com/sbro/emucorec/core/Ps3CoreCallbacks"));
  auto methodId = ensure(env->GetStaticMethodID(
      fwRepositoryClass, "onFirmwareInstalled", "(Ljava/lang/String;)V"));

  env->CallStaticVoidMethod(fwRepositoryClass, methodId, wrap(env, version));
}

static void sendFirmwareCompiled(JNIEnv *env, const std::string &version) {
  auto fwRepositoryClass =
      ensure(env->FindClass("com/sbro/emucorec/core/Ps3CoreCallbacks"));
  auto methodId = ensure(env->GetStaticMethodID(
      fwRepositoryClass, "onFirmwareCompiled", "(Ljava/lang/String;)V"));

  env->CallStaticVoidMethod(fwRepositoryClass, methodId, wrap(env, version));
}

// psf::get_string aborts via ensure() when an entry carries a non-string type,
// and a hand-crafted or corrupt SFO can put any type under any key. All glue
// reads of untrusted SFOs go through this instead.
static std::string_view safe_psf_get_string(const psf::registry& psf,
                                            std::string_view key,
                                            std::string_view def = {}) {
  const auto found = psf.find(key);
  if (found == psf.end()) {
    return def;
  }

  const auto& entry = found->second;
  if (entry.type() != psf::format::string &&
      entry.type() != psf::format::array) {
    return def;
  }

  return entry.as_string();
}

static std::string locateEbootPath(std::string_view root) {
  if (std::filesystem::is_regular_file(root)) {
    return std::string(root);
  }

  for (auto suffix : {
           "/EBOOT.BIN",
           "/USRDIR/EBOOT.BIN",
           "/USRDIR/ISO.BIN.EDAT",
           "/PS3_GAME/USRDIR/EBOOT.BIN",
       }) {
    auto tryPath = std::string(root);
    tryPath += suffix;

    if (std::filesystem::is_regular_file(tryPath)) {
      return tryPath;
    }
  }

  return {};
}

static std::string locateParamSfoPath(std::string_view root) {
  if (std::filesystem::is_regular_file(root)) {
    return std::string(root);
  }

  for (auto suffix : {
           "/PARAM.SFO",
           "/PS3_GAME/PARAM.SFO",
       }) {
    auto tryPath = std::string(root);
    tryPath += suffix;

    if (std::filesystem::is_regular_file(tryPath)) {
      return tryPath;
    }
  }

  return {};
}

class MainThreadProcessor {
  std::mutex mutex;
  std::condition_variable cv;
  std::deque<std::pair<std::function<void(JNIEnv *)>, atomic_t<u32> *>> queue;

public:
  void push(std::function<void(JNIEnv *)> cb, atomic_t<u32> *wakeUp = nullptr) {
    std::lock_guard lock(mutex);
    queue.push_back({std::move(cb), wakeUp});
    cv.notify_one();
  }

  void push(std::function<void()> cb, atomic_t<u32> *wakeUp = nullptr) {
    push([cb = std::move(cb)](JNIEnv *) { cb(); }, wakeUp);
  }

  void process(JNIEnv *env) {
    while (true) {
      std::function<void(JNIEnv *)> cb;
      atomic_t<u32> *wakeUp = nullptr;

      {
        std::unique_lock lock(mutex);
        if (queue.empty()) {
          cv.wait(lock);
          continue;
        }

        auto item = std::move(queue.front());
        queue.pop_front();

        cb = std::move(item.first);
        wakeUp = item.second;
      }

      cb(env);
      if (wakeUp) {
        *wakeUp = true;
        wakeUp->notify_all();
      }
    }
  }
} static g_mainThreadProcessor;

static void invokeAsync(std::function<void(JNIEnv *)> cb) {
  g_mainThreadProcessor.push(std::move(cb));
}

void report_native_operation_result(long progress_id, bool success, std::string message) {
  if (progress_id <= 0) return;
  invokeAsync([progress_id, success, message = std::move(message)](JNIEnv* env) {
    Progress progress(env, progress_id);
    if (success) progress.success(1, message);
    else progress.failure(message);
  });
}

static void invokeSync(std::function<void(JNIEnv *)> cb) {
  atomic_t<u32> wakeup{false};
  g_mainThreadProcessor.push(std::move(cb), &wakeup);

  while (wakeup.load() == false) {
    wakeup.wait(false);
  }
}

struct ProgressMessageDialog : MsgDialogBase {
  jlong progressId;
  jlong value = 0;
  jlong max = 0;

  ProgressMessageDialog(jlong progressId) : progressId(progressId) {}

  void Create(const std::string &msg, const std::string &title) override {
    max = 100;
    invokeSync([this, &msg](JNIEnv *env) {
      Progress progress(env, progressId);
      progress.report(0, 0, msg);
    });
  }

  jlong getValue() const {
    return value == max && max != 0 ? value - 1 : value;
  }

  void Close(bool success) override {
    invokeSync([this](JNIEnv *env) {
      Progress progress(env, progressId);
      progress.report(0, 0);
    });

    //   Progress progress(env, progressId);
    //   if (success) {
    //     progress.success(0);
    //   } else {
    //     progress.failure();
    //   }
    // });
  }

  void SetMsg(const std::string &msg) override {
    invokeSync([this, msg](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max, msg);
    });
  }

  void ProgressBarSetMsg(u32 progressBarIndex,
                         const std::string &msg) override {
    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    invokeSync([this, msg](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max, msg);
    });
  }

  void ProgressBarReset(u32 progressBarIndex) override {
    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    value = 0;
    invokeSync(
        [this](JNIEnv *env) { Progress(env, progressId).report(value, max); });
  }

  void ProgressBarInc(u32 progressBarIndex, u32 delta) override {
    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    value += delta;

    invokeSync([this](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max);
    });
  }

  void ProgressBarSetValue(u32 progressBarIndex, u32 value) override {
    if (progressBarIndex != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    this->value = value;

    invokeSync([this](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max);
    });
  }
  void ProgressBarSetLimit(u32 index, u32 limit) override {
    if (index != 0) {
      report_fatal_error("Unexpected progress index in progress dialog");
    }

    max = limit;

    invokeSync([this](JNIEnv *env) {
      Progress(env, progressId).report(getValue(), max);
    });
  }
};

struct UiMessageDialog : MsgDialogBase {
  // FIXME: implement

  void Create(const std::string &msg, const std::string &title) override {}
  void Close(bool success) override {}
  void SetMsg(const std::string &msg) override {}
  void ProgressBarSetMsg(u32 progressBarIndex,
                         const std::string &msg) override {}
  void ProgressBarReset(u32 progressBarIndex) override {}
  void ProgressBarInc(u32 progressBarIndex, u32 delta) override {}
  void ProgressBarSetValue(u32 progressBarIndex, u32 value) override {}
  void ProgressBarSetLimit(u32 index, u32 limit) override {}
};

struct MessageDialog : MsgDialogBase {
  std::unique_ptr<MsgDialogBase> impl = nullptr;

  void Create(const std::string &msg, const std::string &title) override {
    auto progressId = s_pendingProgressId.load();

    emucorec_android.warning("MessageDialog::Create(%s, %s): source %s, id %d",
                          msg, title, source, progressId);

    if (progressId != -1) {
      impl = std::make_unique<ProgressMessageDialog>(progressId);
    } else {
      impl = std::make_unique<UiMessageDialog>();
    }

    impl->type = type;
    impl->source = source;
    impl->Create(msg, title);
  }

  void Close(bool success) override { impl->Close(success); }

  void SetMsg(const std::string &msg) override { impl->SetMsg(msg); }

  void ProgressBarSetMsg(u32 progressBarIndex,
                         const std::string &msg) override {
    impl->ProgressBarSetMsg(progressBarIndex, msg);
  }

  void ProgressBarReset(u32 progressBarIndex) override {
    impl->ProgressBarReset(progressBarIndex);
  }

  void ProgressBarInc(u32 progressBarIndex, u32 delta) override {
    impl->ProgressBarInc(progressBarIndex, delta);
  }

  void ProgressBarSetValue(u32 progressBarIndex, u32 value) override {
    impl->ProgressBarSetValue(progressBarIndex, value);
  }

  void ProgressBarSetLimit(u32 index, u32 limit) override {
    impl->ProgressBarSetLimit(index, limit);
  }

  static void pushPendingProgressId(jlong id) {
    jlong value = -1;

    while (!s_pendingProgressId.compare_exchange_weak(value, id)) {
      s_pendingProgressId.wait(value);
      value = -1;
    }
  }

  static bool popPendingProgressId(jlong id) {
    return s_pendingProgressId.compare_exchange_strong(id, -1);
  }

private:
  static std::atomic<jlong> s_pendingProgressId;
};

struct OverlaySaveDialog : SaveDialogBase {
  s32 ShowSaveDataList(const std::string &base_dir,
                       std::vector<SaveDataEntry> &save_entries, s32 focused,
                       u32 op, vm::ptr<CellSaveDataListSet> listSet,
                       bool enable_overlay) override {
    emucorec_android.notice("ShowSaveDataList(save_entries=%d, focused=%d, "
                         "op=0x%x, listSet=*0x%x, enable_overlay=%d)",
                         save_entries.size(), focused, op, listSet,
                         enable_overlay);

    bool use_end = sysutil_send_system_cmd(CELL_SYSUTIL_DRAWING_BEGIN, 0) >= 0;

    auto atExit = AtExit([&] {
      if (use_end) {
        sysutil_send_system_cmd(CELL_SYSUTIL_DRAWING_END, 0);
      }
    });

    if (!use_end) {
      emucorec_android.error(
          "ShowSaveDataList(): Not able to notify DRAWING_BEGIN callback "
          "because one has already been sent!");
    }

    if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>()) {
      emucorec_android.notice("ShowSaveDataList: Showing native UI dialog");

      s32 result = manager->create<rsx::overlays::save_dialog>()->show(
          base_dir, save_entries, focused, op, listSet, enable_overlay);

      if (result != rsx::overlays::user_interface::selection_code::error) {
        emucorec_android.notice(
            "ShowSaveDataList: Native UI dialog returned with selection %d",
            result);

        return result;
      }

      emucorec_android.error("ShowSaveDataList: Native UI dialog returned error");
    }

    return -2;
  }
};

class OverlayTrophyNotification : public TrophyNotificationBase {
public:
  s32 ShowTrophyNotification(
      const SceNpTrophyDetails &trophy,
      const std::vector<uchar> &trophy_icon_buffer) override {
    if (auto manager = g_fxo->try_get<rsx::overlays::display_manager>()) {
      auto popup = std::make_shared<rsx::overlays::trophy_notification>();
      return manager->add(popup, false)->show(trophy, trophy_icon_buffer);
    }

    return 0;
  }
};

std::atomic<jlong> MessageDialog::s_pendingProgressId = -1;

struct CompilationWorkload {
  jlong progressId;
  std::string path;
};

extern bool ppu_load_exec(const ppu_exec_object &, bool virtual_load,
                          const std::string &, utils::serial * = nullptr);
extern void spu_load_exec(const spu_exec_object &);
extern void spu_load_rel_exec(const spu_rel_object &);
// Upstream gained a third parameter (is_fast_compilation) after this fork diverged.
extern void ppu_precompile(std::vector<std::string> &dir_queue,
                           std::vector<ppu_module<lv2_obj> *> *loaded_prx,
                           bool is_fast_compilation);
extern bool ppu_initialize(const ppu_module<lv2_obj> &, bool check_only = false,
                           u64 file_size = 0);
extern void ppu_finalize(const ppu_module<lv2_obj> &);
extern bool ppu_load_rel_exec(const ppu_rel_object &);

// Compilation, disc probing, boot and shutdown all own the same VM/VFS state.
std::mutex g_emu_lifecycle_mutex;

class CompilationQueue {
  std::mutex queueMutex;
  std::condition_variable queueReady;
  std::deque<CompilationWorkload> queue;

public:
  void push(CompilationWorkload workload) {
    {
      std::lock_guard lock(queueMutex);
      queue.push_back(std::move(workload));
    }

    queueReady.notify_one();
  }

  void push(Progress &progress, std::string path) {
    progress.report(0, 0);

    push({
        .progressId = progress.getProgressId(),
        .path = std::move(path),
    });
  }

  // Fire-and-forget cache build with no progress id attached: nothing in the
  // app waits for it and no dialog owns it. Used for the VSH cache after a
  // firmware install, which must not keep the installer busy (see installPup).
  void pushDetached(std::string path) {
    push({
        .progressId = -1,
        .path = std::move(path),
    });
  }

  void process(JNIEnv *env) {
    while (true) {
      CompilationWorkload workload;

      {
        std::unique_lock lock(queueMutex);
        queueReady.wait(lock, [this] { return !queue.empty(); });

        workload = std::move(queue.front());
        queue.pop_front();
      }

      const jlong progressId = workload.progressId;
      try {
        impl(env, std::move(workload));
      } catch (const std::exception &error) {
        emucorec_android.error("Compilation failed: %s", error.what());
        if (progressId >= 0) {
          Progress(env, progressId).failure("Compilation failed");
        }
      }
    }
  }

private:
  void impl(JNIEnv *env, CompilationWorkload workload) {
    if (workload.path.empty()) {
      if (workload.progressId >= 0) {
        Progress(env, workload.progressId).success(0);
      }
      return;
    }

    emucorec_android.notice("Creating cache initiated, state %d",
                        (int)Emu.GetStatus(false));

    std::unique_lock lifecycle_lock(g_emu_lifecycle_mutex, std::defer_lock);
    while (true) {
      lifecycle_lock.lock();
      auto state = Emu.GetStatus(false);

      if (state == system_state::stopped) {
        break;
      }

      lifecycle_lock.unlock();
      // No per-second log here: a game boot right after an install can leave
      // this loop waiting for the compiler for a while, and a line per tick is
      // exactly the kind of periodic noise the log must not carry.
      std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    bool is_vsh = workload.path.ends_with("/vsh.self");


    if (workload.progressId >= 0) {
      MessageDialog::pushPendingProgressId(workload.progressId);
    }
    bool cleanup_done = false;
    auto cleanup = [&] {
      if (cleanup_done) return;
      cleanup_done = true;
      Emu.Kill(false);
      while (!Emu.IsStopped(true)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
      }
      if (workload.progressId >= 0) {
        MessageDialog::popPendingProgressId(workload.progressId);
      }
    };
    AtExit cleanup_on_failure{cleanup};
    // The compiler observes the emulator state as its cancellation signal.
    // Use the core's offline execution mode and pair it with normal teardown.
    Emu.SetTestMode();

    g_fxo->init<named_thread<progress_dialog_server>>();
    g_fxo->init<main_ppu_module<lv2_obj>>();

    // patch_engine must exist BEFORE ppu_load_exec, which calls
    // g_fxo->get<patch_engine>().apply() on every segment. g_fxo->get() on an
    // object that was never created hands back raw uninitialised storage, so the
    // patch map's hash table is garbage and apply() segfaults.
    //
    // need<> rather than init<>: it creates only if absent, so it stays correct
    // when several modules are compiled in a row. init<> would return null the
    // second time and the ensure() would abort.
    //
    // Emulator::Load() does exactly this at System.cpp:1766, well before it
    // loads anything.
    g_fxo->need<patch_engine>();
    g_fxo->get<patch_engine>().append_global_patches();

    // Map the PS3 address space.
    //
    // Emulator::Init() does NOT do this -- only the boot paths in
    // Emulator::Load() do, and precompilation deliberately never boots. Without
    // it vm::get() has no blocks, so vm::alloc() returns 0, ppu_register_range()
    // is handed address 0, and its ensure(vm::page_protect(...)) aborts the
    // process with SIGTRAP the moment firmware finishes installing.
    //
    // Load() calls this immediately before ppu_load_exec for the same reason.
    vm::init();
    auto rootPath = std::filesystem::path(workload.path);

    // The previous workload ended with g_fxo->reset(), which destroyed the
    // vfs_manager along with everything else; the vsh workload of a fresh
    // install is the first thing to run, so it only worked by accident (the
    // manager was still alive from core startup). vfs::mount() lazily
    // re-creates it, so re-mount the standard devices the way
    // Emulator::Init() does, or ppu_load_exec's vfs::get("/dev_flash/")
    // check in ppu_check_patch_spu_images aborts the process.
    {
      const std::string emu_dir = rpcs3::utils::get_emu_dir();
      const std::string dev_hdd0 = g_cfg_vfs.get(g_cfg_vfs.dev_hdd0, emu_dir);
      const std::string dev_flash = g_cfg_vfs.get_dev_flash();

      vfs::mount("/dev_hdd0", dev_hdd0);
      vfs::mount("/dev_flash", dev_flash);
      vfs::mount("/dev_flash2", g_cfg_vfs.get_dev_flash2());
      vfs::mount("/dev_flash3", g_cfg_vfs.get_dev_flash3());
      vfs::mount("/app_home",
                 g_cfg_vfs.app_home.to_string().empty()
                     ? rootPath.string() + "/"
                     : g_cfg_vfs.get(g_cfg_vfs.app_home, emu_dir));
      vfs::mount("/dev_bdvd", g_cfg_vfs.get(g_cfg_vfs.dev_bdvd, emu_dir));

      for (const auto &[key, value] : g_cfg_vfs.dev_usb.get_map()) {
        const cfg::device_info usb_info =
            g_cfg_vfs.get_device(g_cfg_vfs.dev_usb, key, emu_dir);
        if (fs::is_dir(usb_info.path)) {
          vfs::mount(key, usb_info.path);
        }
      }
    }

    if (is_vsh) {
      rootPath = g_cfg_vfs.get_dev_flash() + "sys/external/";
    } else {
      if (!std::filesystem::is_directory(rootPath)) {
        rootPath = rootPath.parent_path();
        if (rootPath.filename() == "USRDIR") {
          rootPath = rootPath.parent_path();
        }
      }
    }

    auto &_main = *ensure(g_fxo->try_get<main_ppu_module<lv2_obj>>());

    if (fs::is_file(workload.path)) {
      if (!is_vsh) {
        auto sfoPath = locateParamSfoPath(std::string(rootPath));

        if (!sfoPath.empty()) {
          const auto psf = psf::load_object(sfoPath);
          emucorec_android.notice("title id is %s",
                                  safe_psf_get_string(psf, "TITLE_ID"));

          // EmuCoreC: title id is set by Emu.BootGame on the real boot; this
          // precompilation pass does not own the running game title.
        } else {
          emucorec_android.notice("param.sfo not found");
        }
      }

      // Compile binary first
      emucorec_android.notice("Trying to load binary: %s", workload.path);

      fs::file src{workload.path};
      src = decrypt_self(src);

      const ppu_exec_object obj = src;

      if (obj == elf_error::ok && ppu_load_exec(obj, true, workload.path)) {
        _main.path = workload.path;
      } else {
        emucorec_android.error("Failed to load binary '%s' (%s)", workload.path,
                            obj.get_error());
      }
    }

    // Bulk-init the fixed objects AFTER loading, which is the order
    // Emulator::Load() uses (ppu_load_exec at System.cpp:2603, then
    // g_fxo->init(false) at :2618).
    //
    // Doing it first looks harmless and is not: ppu_load_exec ->
    // ppu_initialize_modules does its own `ensure(g_fxo->init<hle_vars_save>())`,
    // and g_fxo->init returns NULL when the object already exists. So a bulk
    // init beforehand makes that ensure() abort the process.
    g_fxo->init(false, nullptr);

    std::vector<std::string> dir_queue;
    dir_queue.push_back(rootPath.string());

    for (auto &entry :
         std::filesystem::recursive_directory_iterator(rootPath)) {
      if (entry.is_directory()) {
        dir_queue.push_back(entry.path().string());
      }
    }

    std::vector<ppu_module<lv2_obj> *> mod_list;
    emucorec_android.notice("Going to analyze executable");

    // FIXME: split states
    if (!is_vsh) {
      if (_main.analyse(0, _main.elf_entry, _main.seg0_code_end,
                        _main.applied_patches, std::vector<u32>{})) {
        Emu.ConfigurePPUCache();
        emucorec_android.error("Going to precompile main PPU module");
        ppu_initialize(_main);
        mod_list.emplace_back(&_main);
      }
    }

    ppu_precompile(dir_queue, mod_list.empty() ? nullptr : &mod_list, false);

    emucorec_android.notice("Finalization");
    // Complete only after the core has joined its threads and closed the VM.
    cleanup();
    if (is_vsh) {
      sendFirmwareCompiled(env, utils::get_firmware_version());
    }
    if (workload.progressId >= 0) {
      Progress(env, workload.progressId).success(0);
    }
  }
} static g_compilationQueue;

// Runs the callbacks Emu posts to the main thread.
//
// CallFromMainThread with no wake_up is a POST, not a call: upstream hands it to the GUI
// thread and returns immediately. Running it inline instead executes it under whatever
// locks the caller happens to hold, and lv2_obj::sleep_unlocked posts one while holding
// lv2_obj::g_mutex -- the comment upstream put on that call site says to run it on the main
// thread for exactly that reason. The callback is FinalizeRunRequest, the wake for a
// restored savestate, so it took g_mutex against itself and every thread stopped there:
// loading a state, and saving one (a save stops and restarts into the state), parked with
// the SPUs spinning and the progress overlay frozen on the figure it last drew.
//
// Callers that pass wake_up are synchronising on completion (BlockingCallFromMainThread
// waits on it), so those keep running inline exactly as before.
static struct main_thread_dispatcher {
  std::mutex mutex;
  std::condition_variable cv;
  std::deque<std::function<void()>> queue;
  std::thread worker;
  bool started = false;

  void post(std::function<void()> cb) {
    std::unique_lock lock(mutex);

    if (!started) {
      started = true;
      worker = std::thread([this] { run(); });
      worker.detach();
    }

    queue.push_back(std::move(cb));
    cv.notify_one();
  }

  void run() {
    pthread_setname_np(pthread_self(), "Main Callbacks");

    for (;;) {
      std::function<void()> cb;

      {
        std::unique_lock lock(mutex);
        cv.wait(lock, [this] { return !queue.empty(); });
        cb = std::move(queue.front());
        queue.pop_front();
      }

      // One callback throwing must not take the dispatcher with it: everything posted
      // after it would be dropped, including the savestate wake.
      try {
        cb();
      } catch (const std::exception &e) {
        emucorec_android.error("Main-thread callback threw: %s", e.what());
      } catch (...) {
        emucorec_android.error("Main-thread callback threw an unknown exception");
      }
    }
  }
} g_mainThreadDispatcher;

static void setupCallbacks() {
  g_emu_callbacks = {
      .call_from_main_thread =
          [](std::function<void()> cb, atomic_t<u32> *wake_up) {
            if (wake_up) {
              // The caller is waiting on this one; running it here is what it
              // already did and is what BlockingCallFromMainThread expects.
              cb();
              *wake_up = true;
              wake_up->notify_one();
              return;
            }

            g_mainThreadDispatcher.post(std::move(cb));
          },
      .on_run = [](auto...) {},
      .on_run_completed = save_states::running,
      .on_pause = [](auto...) {},
      .on_resume = [](auto...) {},
      .on_stop = [] { save_states::stopped(); usb_android_release_devices(); },
      .on_ready = [](auto...) {},
      .on_missing_fw = [](auto...) {},
      // RPCS3's stop watchdog calls this when shutting the VM down has taken about 10
      // seconds, and again later if it is still going. It was a no-op, so the one thing
      // upstream does about a deadlocked stop -- tell somebody -- did not happen here: the
      // join thread went on spinning, the progress overlay sat at its last figure, and the
      // app looked frozen with nothing in the log to say why.
      //
      // Reported, not aborted. The desktop build offers to terminate, but it asks first, and
      // a stop that is merely slow is not the same as one that is stuck: a savestate write
      // was measured here taking 71 seconds legitimately. Killing the process on a timer
      // would trade a hang for a corrupted save, so this says what is happening and leaves
      // the choice to the user, who can still close the app.
      .on_emulation_stop_no_response =
          [](std::shared_ptr<atomic_t<bool>> closed_successfully,
             int seconds_waiting_already) {
            if (closed_successfully && *closed_successfully) {
              return;
            }

            emucorec_android.error(
                "Emulation has not stopped after %d seconds. A thread is not "
                "responding to the stop request; the app will stay on the last "
                "frame until it does.",
                seconds_waiting_already);

            // Name the threads that have not stopped, and their state flags.
            //
            // This is the whole diagnosis and it cannot be recovered from outside: by the
            // time the hang can be looked at, /proc shows a sleeping thread with no CPU time
            // and nothing about WHY. The flags say whether it was ever told to exit
            // (cpu_flag::exit), whether it is parked waiting (cpu_flag::wait), or whether it
            // never left its initial stopped state, which is what a thread with no CPU time
            // at all suggests.
            idm::select<named_thread<spu_thread>>([](u32 id, spu_thread &spu) {
              emucorec_android.error("  SPU 0x%x has not stopped, state = %s", id,
                                  spu.state.load());
            });
          },
      .on_save_state_progress = [](auto...) {},
      .on_save_state_completed = save_states::completed,
      .enable_disc_eject = [](auto...) {},
      .enable_disc_insert = [](auto...) {},
      .try_to_quit = [](bool, std::function<void()> on_exit) {
        if (on_exit) on_exit();
        return true;
      },
      .handle_taskbar_progress = [](auto...) {},
      .init_kb_handler =
          [](auto...) {
            ensure(g_fxo->init<KeyboardHandlerBase, NullKeyboardHandler>(
                Emu.DeserialManager()));
          },
      .init_mouse_handler =
          [](auto...) {
            ensure(g_fxo->init<MouseHandlerBase, NullMouseHandler>(
                Emu.DeserialManager()));
          },
      .init_pad_handler =
          [](auto...) {
            ensure(g_fxo->init<named_thread<pad_thread>>(nullptr, nullptr, ""));
          },
      .update_emu_settings = [](auto...) {},
      .save_emu_settings =
          [](auto...) {
            Emulator::SaveSettings(g_cfg.to_string(), Emu.GetTitleID());
          },
      .close_gs_frame = [](auto...) {},
      .get_gs_frame = [] { return std::make_unique<GraphicsFrame>(); },
      .get_camera_handler =
          [](auto...) { return std::make_shared<null_camera_handler>(); },
      .get_music_handler =
          [](auto...) { return std::make_shared<null_music_handler>(); },
      // EmuCoreC: the old bridge injected a pad-handler FACTORY here via a
      // `create_pad_handler` callback that no longer exists -- upstream's
      // EmuCallbacks only has `init_pad_handler(std::string_view title_id)`,
      // and pad_thread::GetHandler() owns construction now. The Android-only
      // virtual_pad case lives there instead (guarded by __ANDROID__, matching
      // the enum in Emu/Io/pad_config_types.h), so upstream keeps ownership of
      // the switch and we do not have to re-list every handler it gains.
      .init_gs_render =
          [](utils::serial *ar) {
            switch (g_cfg.video.renderer.get()) {
            case video_renderer::null:
              g_fxo->init<rsx::thread, named_thread<NullGSRender>>(ar);
              break;
            case video_renderer::vulkan:
              g_fxo->init<rsx::thread, named_thread<VKGSRender>>(ar);
              break;

            // EmuCoreC: the GL backend only exists when RSX_GLES is compiled in.
#ifdef RSX_GLES
            case video_renderer::opengl:
              g_fxo->init<rsx::thread, named_thread<GLGSRender>>(ar);
              break;
#endif

            default:
              break;
            }
          },
      .get_audio =
          [](auto...) {
            std::shared_ptr<AudioBackend> result;

            switch (g_cfg.audio.renderer.get()) {
            case audio_renderer::null:
              result = std::make_shared<NullAudioBackend>();
              break;


            case audio_renderer::cubeb:
            default:
              result = std::make_shared<CubebBackend>();
              break;
            }

            if (!result->Initialized()) {
              emucorec_android.error(
                  "Audio renderer %s could not be initialized, using a Null "
                  "renderer instead. Make sure that no other application is "
                  "running that might block audio access (e.g. Netflix).",
                  result->GetName());
              result = std::make_shared<NullAudioBackend>();
            }
            return result;
          },
      .get_audio_enumerator = [](auto...) { return nullptr; },
      .get_msg_dialog = [] { return std::make_shared<MessageDialog>(); },
      .get_osk_dialog = [](auto...) { return nullptr; },
      .get_save_dialog =
          [](auto...) { return std::make_unique<OverlaySaveDialog>(); },
      .get_sendmessage_dialog = [](auto...) { return nullptr; },
      .get_recvmessage_dialog = [](auto...) { return nullptr; },
      .get_trophy_notification_dialog =
          [](auto...) { return std::make_unique<OverlayTrophyNotification>(); },
      .get_localized_string = [](localized_string_id id,
                                 const char *args) -> std::string {
        const auto *entry = find_localized_string(id);
        if (!entry) {
          return "";
        }
        return substitute_arg<char>(entry->first, args ? args : "");
      },
      .get_localized_u32string = [](localized_string_id id,
                                    const char *args) -> std::u32string {
        const auto *entry = find_localized_string(id);
        if (!entry) {
          return U"";
        }
        // Trophy names reach this one, so the argument has to be decoded rather
        // than widened byte by byte.
        const std::u32string arg = utf8_to_u32string(args ? args : "");
        return substitute_arg<char32_t>(entry->second, arg);
      },
      .get_localized_setting = [](auto...) { return ""; },
      .get_photo_path = [](std::string_view) { return std::string{}; },
      .play_sound = [](auto...) {},
      .get_image_info = [](auto...) { return false; },
      .get_scaled_image = [](auto...) { return false; },
      .resolve_path =
          [](std::string_view arg) {
            std::error_code ec;
            auto result =
                std::filesystem::weakly_canonical(
                    std::filesystem::path(fmt::replace_all(arg, "\\", "/")), ec)
                    .string();
            return ec ? std::string(arg) : result;
          },
      .get_font_dirs = [](auto...) { return std::vector<std::string>(); },
      .on_install_pkgs =
          [](const std::vector<std::string> &pkgs, bool from_optical_drive) {
            for (const std::string &pkg : pkgs) {
              if (!rpcs3::utils::install_pkg(pkg, from_optical_drive)) {
                emucorec_android.error("cd install pkgs: failed to install %s",
                                    pkg);
                return false;
              }
            }
            return true;
          },
      .add_breakpoint = [](auto...) {},
      .display_sleep_control_supported = [](auto...) { return false; },
      .enable_display_sleep = [](auto...) {},
      .check_microphone_permissions = [](auto...) {},
      .make_video_source = []() -> std::unique_ptr<video_source> { return nullptr; },
      // These callbacks were once left empty. Emulator::Load invokes both
      // unconditionally, which aborted Android boots with bad_function_call.
      // GameMode is a Linux desktop daemon and has no Android equivalent.
      .enable_gamemode = [](bool) {},
      // Recommended settings for this title, as config YAML content. The app
      // splits the bundled api.rpcs3.net snapshot (see ConfigDatabaseSeeder)
      // into one file per title because the Qt downloader used by desktop
      // RPCS3 is not part of this build.
      .get_database_config =
          [](const std::string &title_id) -> std::string {
            if (title_id.empty()) {
              return {};
            }

            const std::string path =
                fs::get_config_dir(true) + "config_db/" + title_id + ".yml";

            if (!fs::is_file(path)) {
              return {};
            }

            fs::file config{path};
            if (!config) {
              return {};
            }

            emucorec_android.notice("using database config for %s", title_id);
            return config.to_string();
          },
  };
}

static bool initVirtualPad(const std::shared_ptr<Pad> &pad) {
  u32 pclass_profile = 0;
  pad->Init(CELL_PAD_STATUS_CONNECTED,
            CELL_PAD_CAPABILITY_PS3_CONFORMITY |
                CELL_PAD_CAPABILITY_PRESS_MODE |
                CELL_PAD_CAPABILITY_HP_ANALOG_STICK |
                CELL_PAD_CAPABILITY_ACTUATOR //| CELL_PAD_CAPABILITY_SENSOR_MODE
            ,
            CELL_PAD_DEV_TYPE_STANDARD, CELL_PAD_PCLASS_TYPE_STANDARD,
            pclass_profile, 0, 0, 50);

  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_UP);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_DOWN);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_LEFT);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_RIGHT);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_CROSS);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_SQUARE);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_CIRCLE);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_TRIANGLE);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_L1);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_L2);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_L3);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_R1);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL2, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_R2);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_R3);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_START);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_SELECT);
  pad->m_buttons.emplace_back(CELL_PAD_BTN_OFFSET_DIGITAL1, std::vector<std::set<u32>>{},
                              CELL_PAD_CTRL_PS);

  pad->m_sticks[0] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_LEFT_X, {}, {});
  pad->m_sticks[1] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_LEFT_Y, {}, {});
  pad->m_sticks[2] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_RIGHT_X, {}, {});
  pad->m_sticks[3] = AnalogStick(CELL_PAD_BTN_OFFSET_ANALOG_RIGHT_Y, {}, {});

  pad->m_sensors[0] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_X, 0, 0, 0, DEFAULT_MOTION_X);
  pad->m_sensors[1] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_Y, 0, 0, 0, DEFAULT_MOTION_Y);
  pad->m_sensors[2] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_Z, 0, 0, 0, DEFAULT_MOTION_Z);
  pad->m_sensors[3] =
      AnalogSensor(CELL_PAD_BTN_OFFSET_SENSOR_G, 0, 0, 0, DEFAULT_MOTION_G);

  pad->m_vibrate_motors[0] = VibrateMotor(true);
  pad->m_vibrate_motors[1] = VibrateMotor(false);

  if (pad->m_player_id < g_virtual_pads.size()) {
    std::lock_guard lock(g_virtual_pad_mutex);
    g_virtual_pads[pad->m_player_id] = pad;
  }
  return true;
}

extern "C" bool emucorec_overlayPadData(int port, int digital1, int digital2,
                                      int leftStickX, int leftStickY,
                                      int rightStickX, int rightStickY) {

  if (port < 0 || static_cast<usz>(port) >= g_virtual_pads.size()) {
    return false;
  }

  auto pad = [port] {
    std::shared_ptr<Pad> result;
    std::lock_guard lock(g_virtual_pad_mutex);
    result = g_virtual_pads[port];
    return result;
  }();

  if (pad == nullptr) {
    return false;
  }

  for (auto &btn : pad->m_buttons) {
    if (btn.m_offset == CELL_PAD_BTN_OFFSET_DIGITAL1) {
      btn.m_pressed = (digital1 & btn.m_outKeyCode) != 0;

      if (btn.m_outKeyCode == CELL_PAD_CTRL_PS && btn.m_pressed) {
        if (auto padThread = pad::get_pad_thread(true)) {
          padThread->open_home_menu();
        }
      }

    } else if (btn.m_offset == CELL_PAD_BTN_OFFSET_DIGITAL2) {
      btn.m_pressed = (digital2 & btn.m_outKeyCode) != 0;
    }

    // 255 is what cellPad copies into the press byte for a fully pressed
    // button. Triggers are thresholded in the app (InputOverlay), so every
    // button this export drives is digital by design.
    btn.m_value = btn.m_pressed ? 255 : 0;
  }

  pad->m_sticks[0].m_value = leftStickX;
  pad->m_sticks[1].m_value = leftStickY;
  pad->m_sticks[2].m_value = rightStickX;
  pad->m_sticks[3].m_value = rightStickY;
  return true;
}

// Hand the core Android's exact SoC identity. Deliberately a separate export
// rather than an extra emucorec_initialize parameter: the core is dlopen()ed and
// can be updated independently of the JNI glue that calls it, so changing an
// existing export's signature would make older glue call it with a garbage
// argument. Glue that predates this export simply never calls it, and glue that
// has it null-checks the symbol against older cores.
//
// Must be called before emucorec_initialize, which is where the startup messages
// are assembled.
extern "C" void emucorec_setSocInfo(std::string_view socInfo) {
  g_android_soc_info = std::string(socInfo);
}

extern "C" bool emucorec_initialize(std::string_view rootDir,
                                  std::string_view user) {
  auto rootDirStr = fix_dir_path(std::string(rootDir));

  if (g_android_executable_dir != rootDirStr) {
    g_android_executable_dir = rootDirStr;
    g_android_config_dir = rootDirStr + "config/";
    g_android_cache_dir = rootDirStr + "cache/";

#ifdef EMUCOREC_PGO_GENERATE
  // Now, not earlier: the path is built from g_android_cache_dir.
  pgo_set_output_path();
#endif

    std::filesystem::create_directories(g_android_config_dir);
    std::error_code ec;
    // std::filesystem::remove_all(g_android_cache_dir, ec);
    std::filesystem::create_directories(g_android_cache_dir);

  }

  if (g_initialized) {
    return true;
  }

  g_initialized = true;

  if (int r = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY,
                                nullptr);
      r != 0) {
    emucorec_android.warning(
        "libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY) -> %d", r);
  }

  // Initialize thread pool finalizer // ???
  static_cast<void>(named_thread("", [](int) {}));

  static std::unique_ptr<logs::listener> log_file;
  {
    // Check free space
    fs::device_stat stats{};
    if (!fs::statfs(fs::get_cache_dir(), stats) ||
        stats.avail_free < 128 * 1024 * 1024) {
      std::fprintf(stderr, "Not enough free space for logs (%f KB)",
                   stats.avail_free / 1000000.);
    }

    // preserve old log file
    if (std::filesystem::exists(fs::get_log_dir() + "emucorec.log")) {
      std::error_code ec;
      std::filesystem::remove(fs::get_log_dir() + "emucorec.old.log", ec);
      std::filesystem::rename(fs::get_log_dir() + "emucorec.log",
                              fs::get_log_dir() + "emucorec.old.log", ec);
    } else if (std::filesystem::exists(fs::get_log_dir() + "emucorec.log")) {
      // One-time migration: keep the previous session's log readable under the
      // new name instead of orphaning the file the old release wrote.
      std::error_code ec;
      std::filesystem::remove(fs::get_log_dir() + "emucorec.old.log", ec);
      std::filesystem::rename(fs::get_log_dir() + "emucorec.log",
                              fs::get_log_dir() + "emucorec.old.log", ec);
    }

    // Limit log size to ~25% of free space
    log_file = logs::make_file_listener(fs::get_log_dir() + "emucorec.log",
                                        stats.avail_free / 4);
  }

  // Mesa driver options, from <root>/driver_env.txt, one NAME=VALUE per line.
  //
  // This device needs Turnip -- the stock Adreno driver does not render the game at all -- and
  // Turnip is steered by environment variables such as TU_DEBUG. The usual way to set one,
  // the wrap.<package> property, is ignored on a user build: it can be set and read back while
  // never reaching the process, so a flag that never applied looks exactly like a flag that
  // made no difference.
  //
  // Here rather than earlier, because this is the first point the log file exists; anything
  // written before it is opened is discarded, and the whole value of this is being able to see
  // that the option was applied. Still long before any Vulkan instance, which is what matters:
  // Mesa caches each option the first time it is read.
  //
  // A missing file does nothing, which is the normal case.
  if (std::ifstream env_file(g_android_executable_dir + "driver_env.txt"); env_file.is_open()) {
    std::string line;
    while (std::getline(env_file, line)) {
      if (const auto eq = line.find('=');
          eq != std::string::npos && !line.empty() && line[0] != '#') {
        auto name = line.substr(0, eq);
        auto value = line.substr(eq + 1);

        while (!value.empty() && (value.back() == '\r' || value.back() == ' ')) {
          value.pop_back();
        }

        setenv(name.c_str(), value.c_str(), 1);

        // Read it back rather than echoing what we meant to set. /proc/<pid>/environ is the
        // snapshot taken at exec and never reflects a runtime setenv, and Mesa's own logging
        // may go to stderr, which Android drops, so this is the only honest confirmation.
        emucorec_android.notice("driver_env: %s=%s", name, getenv(name.c_str()));
      }
    }
  }

  logs::stored_message ver{emucorec_android.always()};
  ver.text = fmt::format("EmuCoreC-ps3-android v%s", rpcs3::get_version().to_string());

  // Write exact SoC identity (from Android, not inferred from MIDRs)
  logs::stored_message soc{emucorec_android.always()};
  soc.text = fmt::format(
      "SoC: %s", g_android_soc_info.empty() ? "unknown" : g_android_soc_info);

  // Write System information
  logs::stored_message sys{emucorec_android.always()};
  sys.text = utils::get_system_info();

  // Write OS version
  logs::stored_message os{emucorec_android.always()};
  os.text = utils::get_OS_version_string();

  // Write current time
  logs::stored_message time{emucorec_android.always()};
  time.text = fmt::format("Current Time: %s", std::chrono::system_clock::now());

  logs::set_init({std::move(ver), std::move(soc), std::move(sys), std::move(os),
                  std::move(time)});

  auto set_rlim = [](int resource, std::uint64_t limit) {
    rlimit64 rlim{};
    if (getrlimit64(resource, &rlim) != 0) {
      emucorec_android.error("failed to get rlimit for %d", resource);
      return;
    }

    rlim.rlim_cur = std::min<std::size_t>(rlim.rlim_max, limit);
    emucorec_android.notice("rlimit[%d] = %u (requested %u, max %u)", resource,
                        rlim.rlim_cur, limit, rlim.rlim_max);

    if (setrlimit64(resource, &rlim) != 0) {
      emucorec_android.error("failed to set rlimit for %d", resource);
      return;
    }
  };

  set_rlim(RLIMIT_MEMLOCK, RLIM_INFINITY);
  set_rlim(RLIMIT_NOFILE, RLIM_INFINITY);
  set_rlim(RLIMIT_STACK, 128 * 1024 * 1024);
  set_rlim(RLIMIT_AS, RLIM_INFINITY);

  virtual_pad_handler::set_on_connect_cb(initVirtualPad);
  setupCallbacks();
  Emu.SetHasGui(false);
  Emu.SetUsr(std::string(user));

  // Tell the core which renderers exist.
  //
  // m_supported_renderers defaults to {null} (System.cpp:124) and the desktop
  // frontend is what normally populates it. Nothing did here, so ApplySettings
  // logged "The video renderer 'Vulkan' is not supported on this device and will
  // therefore be set to 'Null'" and forced Null -- after which every boot failed
  // and tripped the ensure() in BootGame's restore-on-no-boot path.
  //
  // Emu.Init() also asserts a non-empty adapter when the default is Vulkan
  // (System.cpp:493), and an empty string means "first device" to vk::instance,
  // so it has to be a real name rather than left blank.
  std::set<video_renderer> supported_renderers{
      video_renderer::null,
      video_renderer::vulkan,
  };
#ifdef RSX_GLES
  supported_renderers.insert(video_renderer::opengl);
#endif
  Emu.SetSupportedRenderers(std::move(supported_renderers));
  Emu.SetDefaultRenderer(video_renderer::vulkan);
  Emu.SetDefaultGraphicsAdapter("Default");

  Emu.Init();

#ifndef RSX_GLES
  // Migrate configurations written by older Android builds which exposed the
  // desktop OpenGL enum even though no GL renderer was linked.
  if (g_cfg.video.renderer == video_renderer::opengl) {
    emucorec_android.warning(
        "OpenGL was selected in the saved config but this Android build has no GLES backend; using Vulkan");
    g_cfg.video.renderer.set(video_renderer::vulkan);
    Emulator::SaveSettings(g_cfg.to_string(), "");
  }
#endif

  g_cfg_input.player1.handler.set(pad_handler::virtual_pad);
  g_cfg_input.player1.device.from_string("Virtual");
  g_cfg_input.save("", g_cfg_input_configs.default_config);

  // LLVM JIT target.
  //
  // This was pinned to "cortex-a34" -- an ARMv8.0-A IN-ORDER little core from
  // 2016. Every PPU and SPU block LLVM compiles was therefore scheduled for an
  // in-order pipeline and restricted to the ARMv8.0 baseline, on hardware that is
  // out-of-order ARMv9 (Cortex-X3/A715 on an 8 Gen 2). The hand-written NEON
  // paths were unaffected -- m_use_dotprod / m_use_i8mm come from runtime HWCAP,
  // not from this string -- but everything LLVM generates itself was built for
  // the wrong machine.
  //
  // Empty means "detect the host", which is what desktop RPCS3 does. Feature
  // asymmetry across big.LITTLE is not a hazard here: the kernel requires every
  // core in an SoC to expose the same ISA, so the difference is scheduling, not
  // legality.
  // Thread affinity is gated on this being anything other than `os`, and the
  // affinity path itself was compiled out on Android until now (Thread.cpp), so
  // the setting has never done anything here. With a big.LITTLE mask available
  // it is worth having: six SPU threads scattered across prime and A510 cores
  // run at the speed of the slowest one they land on.
  g_cfg.core.thread_scheduler.set(thread_scheduler_mode::alt);

  g_cfg.core.llvm_cpu.from_string("");

    emucorec_android.notice("LLVM JIT target: '%s'",
                        g_cfg.core.llvm_cpu.to_string().empty()
                            ? "<host-detected>"
                            : g_cfg.core.llvm_cpu.to_string());

  Emulator::SaveSettings(g_cfg.to_string(), Emu.GetTitleID());
  return true;
}

// ---------------------------------------------------------------------------
// JNI_GetCreatedJavaVMs
//
// 3rdparty/rtmidi's Android (AMidi) backend calls this to reach the VM. It is
// part of the JNI Invocation API exported by libnativehelper/ART -- NOT by the
// public NDK, so linking it is impossible and rtmidi is added unconditionally
// with no option to disable. It was the last undefined symbol in the build.
//
// JNI_OnLoad never runs for us: the app dlopen()s this core rather than
// System.loadLibrary()ing it, and dlopen does not invoke JNI_OnLoad. So capture
// the VM from the first JNIEnv that crosses the boundary instead, and report
// zero VMs until then -- rtmidi treats that as "no MIDI available" and degrades
// cleanly rather than crashing.
// ---------------------------------------------------------------------------
static std::atomic<JavaVM *> g_java_vm{nullptr};

static void emucorec_capture_java_vm(JNIEnv *env) {
  if (env == nullptr || g_java_vm.load(std::memory_order_acquire) != nullptr) {
    return;
  }

  JavaVM *vm = nullptr;
  if (env->GetJavaVM(&vm) == JNI_OK && vm != nullptr) {
    JavaVM *expected = nullptr;
    g_java_vm.compare_exchange_strong(expected, vm);
  }
}

extern "C" jint JNI_GetCreatedJavaVMs(JavaVM **vmBuf, jsize bufLen,
                                      jsize *nVMs) {
  JavaVM *vm = g_java_vm.load(std::memory_order_acquire);

  if (nVMs != nullptr) {
    *nVMs = (vm != nullptr) ? 1 : 0;
  }

  if (vm != nullptr && vmBuf != nullptr && bufLen > 0) {
    vmBuf[0] = vm;
  }

  return JNI_OK;
}

extern "C" bool emucorec_processCompilationQueue(JNIEnv *env) {
  emucorec_capture_java_vm(env);
  g_compilationQueue.process(env);
  return true;
}

extern "C" bool emucorec_startMainThreadProcessor(JNIEnv *env) {
  emucorec_capture_java_vm(env);
  g_mainThreadProcessor.process(env);
  return true;
}

// Serialises disc probing against everything that owns g_fxo: booting AND shutdown.
//
// probeDiscInfo mounts the image into the GLOBAL vfs to read its PARAM.SFO, and the library
// scan runs it on a background thread. vfs::mount starts with g_fxo->need<vfs_manager>(),
// so it is not just the mount table that has to be quiet, it is g_fxo itself.
//
// Closing a game calls Emu.Kill(), which resets g_fxo, and closing also returns to the
// library, which starts a rescan. Those two overlap, and the probe aborted the process
// inside vfs::mount. Booting another game immediately made it far more likely, which is
// what "crashes if I open a new game within ~2s, fine after ~10s" actually was: waiting
// let the teardown and the scan finish, not the boot.
//
// An Emu.IsStopped() check alone is not enough. It is a plain read, the state reaches
// stopped before g_fxo teardown has finished, and a boot or kill can start right after it.
// Held so a probe cannot be inside vfs::mount while this resets g_fxo underneath it.
extern "C" void emucorec_shutdown() {
  if (save_states::defer_stop()) return;
  std::lock_guard vfs_lock(g_emu_lifecycle_mutex);
  if (save_states::defer_stop()) return;
  Emu.Kill();
}

extern "C" int emucorec_boot(std::string_view path_) {
  if (save_states::busy()) return static_cast<int>(game_boot_result::still_running);
  // Taken FIRST, before the Kill below, and held across the whole boot.
  //
  // Emu.Kill() spawns an "Emulation Join Thread" that joins every emulator thread. It is
  // not safe to have two of those in flight: they end up joining each other and neither
  // finishes, which pins the emulator in a non-stopped state forever. Observed on device
  // as FOUR live join threads plus six leaked AudioTrack threads after a few close-then-
  // boot cycles, with the join thread logging "Thread [Emulation Join Thread] is too
  // sleepy" against itself.
  //
  // This used to Kill() before taking the lock, so a Close (which calls emucorec_kill) and
  // the boot that followed it each started their own teardown and deadlocked. The pile-up
  // is what left the emulator never reaching stopped, and every later boot then failed and
  // bounced the user back to the library.
  std::lock_guard emu_lock(g_emu_lifecycle_mutex);
  if (save_states::busy()) return static_cast<int>(game_boot_result::still_running);
  if ((path_.ends_with(".SAVESTAT") || path_.ends_with(".SAVESTAT.zst") || path_.ends_with(".SAVESTAT.gz")) &&
      (!save_states::validate_slot_file(std::string(path_)) || !is_savestate_compatible(std::string(path_)))) {
    return static_cast<int>(game_boot_result::invalid_file_or_folder);
  }

  // BootGame's restore_on_no_boot path does ensure(IsStopped()) whenever the boot fails,
  // so entering it while the emulator is still winding down aborts the process outright
  // (System.cpp, "Verification failed (object: 0x0)").
  //
  // Kill() is idempotent and returns quickly when already stopped. The bound is generous
  // because a real teardown joins the RSX and SPU threads and flushes caches.
  // A timeout leaves the old VM alone and reports still_running to the caller.
  if (!Emu.IsStopped(true)) {
    emucorec_android.notice("boot: previous VM still running, stopping it first");
    Emu.Kill();

    for (int waited = 0; !Emu.IsStopped(true) && waited < 10000; waited += 20) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    if (!Emu.IsStopped(true)) {
      emucorec_android.error("boot: previous VM did not stop in time");
      return static_cast<int>(game_boot_result::still_running);
    }
  }

  usb_android_activate_devices();
  Emu.SetForceBoot(true);
  std::string path = std::string(path_);
  while (path.ends_with('/')) {
    path.pop_back();
  }

  // Folder-format games boot through their EBOOT, not the directory.
  //
  // A PKG install and a JB folder dump both land as a directory, and the library
  // boots them by the bootable path the installer reported -- which is the EBOOT.BIN,
  // never the folder. Handing BootGame the directory instead came up as a permanent
  // black screen on an installed title. locateEbootPath handles both layouts
  // (PS3_GAME/USRDIR for a disc, USRDIR for an installed game); if it finds nothing we
  // fall through to the original behaviour rather than failing the boot outright.
  if (fs::is_dir(path)) {
    if (auto eboot = locateEbootPath(path); !eboot.empty() && fs::is_file(eboot)) {
      emucorec_android.notice("boot: resolved directory '%s' to '%s'", path, eboot);
      path = eboot;
    }
  }

  return static_cast<int>(Emu.BootGame(path, "", false, cfg_mode::custom));
}

extern "C" int emucorec_getState() {
  return static_cast<int>(Emu.GetStatus(false));
}
extern "C" void emucorec_kill() {
  if (save_states::defer_stop()) return;
  std::lock_guard vfs_lock(g_emu_lifecycle_mutex);
  if (save_states::defer_stop()) return;
  Emu.Kill();
}
extern "C" void emucorec_resume() { Emu.Resume(); }

// The counterpart to emucorec_resume, which had none. Rpcs3Bridge.pause() set a bool and returned,
// on the belief that "RPCS3 has no explicit pause entry point" -- but Emu.Pause() is right here,
// and emucorec_surfaceEvent has always called it on surface loss. That is why BACKGROUNDING the app
// was the only thing that actually paused, while the in-game pause menu left the emulator running
// underneath it, and why pause/resume were asymmetric: resume() reached the core, pause() did not.
extern "C" void emucorec_pause() {
  // Restoration owns the VM until its run callback or deferred stop completes.
  if (!save_states::busy()) Emu.Pause();
}

extern "C" void emucorec_openHomeMenu() {
  if (save_states::busy()) return;
  std::lock_guard lock(g_emu_lifecycle_mutex);
  if (save_states::busy()) return;
  if (auto padThread = pad::get_pad_thread(true)) {
    padThread->open_home_menu();
  }
}

extern "C" std::string emucorec_getTitleId() { return Emu.GetTitleID(); }


// ---------------------------------------------------------------------------
// Patches / graphics mods
//
// RPCS3 keeps two files:
//   patches/patch.yml   - the patch DATABASE, downloaded from rpcs3.net
//   patch_config.yml    - which patches are ENABLED, keyed
//                         hash -> description -> title -> serial -> app_version
//
// Both are YAML with a fiddly nested shape, and patch_engine already parses and
// writes them. So the app downloads the bytes and everything else happens here,
// through RPCS3's own code -- reimplementing the format in Kotlin would be a
// second parser to keep in sync with upstream.
// ---------------------------------------------------------------------------

/** Import a downloaded patch.yml. Returns the number of patches merged, or -1. */
static std::string json_quote(std::string_view v) {
  std::string out = "\"";
  for (char c : v) {
    switch (c) {
    case '"': out += "\\\""; break;
    case '\\': out += "\\\\"; break;
    case '\n': out += "\\n"; break;
    case '\r': out += "\\r"; break;
    case '\t': out += "\\t"; break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        char buf[8];
        std::snprintf(buf, sizeof(buf), "\\u%04x", c);
        out += buf;
      } else {
        out += c;
      }
    }
  }
  return out + "\"";
}

/**
 * The patch schema version this core can parse.
 *
 * rpcs3.net serves a different schema per version and patch_engine::load
 * rejects a file whose Version header does not match, so the app has to ask
 * for the version built into the core rather than name one itself. It had 1.2
 * hardcoded in the download URL, which is right until upstream bumps the
 * constant and every download starts failing to parse.
 */
extern "C" std::string emucorec_patchEngineVersion() {
  return patch_engine_version;
}

extern "C" int emucorec_patchesImport(std::string_view content) {
  patch_engine::patch_map patches;
  std::stringstream log;

  if (!patch_engine::load(patches, "<downloaded>", std::string(content), true,
                          &log)) {
    emucorec_android.error("patchesImport: parse failed: %s", log.str());
    return -1;
  }

  usz count = 0;
  usz total = 0;
  if (!patch_engine::import_patches(patches, patch_engine::get_patches_path() +
                                                 "patch.yml",
                                    count, total, &log)) {
    emucorec_android.error("patchesImport: write failed: %s", log.str());
    return -1;
  }

  emucorec_android.success("patchesImport: %u of %u patches", count, total);
  return static_cast<int>(count);
}

/**
 * Patches applicable to `serial`, as JSON.
 *
 * Emitted by hand rather than with a JSON library because the core does not link
 * one, and the shape is small and fixed.
 */
// A local archive owns its image and decryption state. Probing metadata never
// mounts /dev_bdvd or changes the running game's VFS or emulator state.
static bool copy_game_icon(fs::file icon, std::string_view iconOut) {
  if (!icon || iconOut.empty() || !icon.size() || icon.size() > 10 * 1024 * 1024) {
    return false;
  }
  const auto bytes = icon.to_vector<u8>();
  if (bytes.empty()) return false;
  fs::pending_file out{iconOut};
  return out.file && out.file.write(bytes.data(), bytes.size()) == bytes.size() && out.commit();
}

static std::string sfo_game_info_json(const psf::registry &psf, bool haveIcon) {
  const auto field = [&](std::string_view name) {
    return json_quote(std::string(safe_psf_get_string(psf, name)));
  };
  const auto version = safe_psf_get_string(psf, "APP_VER",
      safe_psf_get_string(psf, "VERSION", "01.00"));
  if (safe_psf_get_string(psf, "TITLE_ID").empty()) return "{}";
  return "{\"titleId\":" + field("TITLE_ID") + ",\"title\":" + field("TITLE") +
      ",\"version\":" + json_quote(std::string(version)) + ",\"category\":" + field("CATEGORY") +
      ",\"contentId\":" + field("CONTENT_ID") + ",\"saveDataId\":" + field("INSTALL_DIR_SAVEDATA") +
      ",\"icon\":" + (haveIcon ? "true" : "false") + "}";
}

static std::string read_sfo_game_info(const std::string &root,
                                      std::string_view iconOut) {
  fs::file sfo{root + "/PARAM.SFO"};
  if (!sfo) return "{}";
  const auto psf = psf::load_object(sfo, root + "/PARAM.SFO");
  if (safe_psf_get_string(psf, "TITLE_ID").empty()) return "{}";
  const bool haveIcon = !iconOut.empty() &&
      copy_game_icon(fs::file{root + "/ICON0.PNG"}, iconOut);
  return sfo_game_info_json(psf, haveIcon);
}

extern "C" std::string emucorec_probeDiscInfo(std::string_view isoPath,
                                            std::string_view iconOut) {
  if (isoPath.empty()) return "{}";
  const std::string path(isoPath);
  try {
    if (fs::is_dir(path)) {
      const auto info = read_sfo_game_info(path + "/PS3_GAME", iconOut);
      return info != "{}" ? info : read_sfo_game_info(path, iconOut);
    }
    iso_archive archive(path);
    if (!archive.is_valid() || archive.get_key_status() != iso_key_status::OK) {
      return "{}";
    }
    const auto psf = archive.open_psf("PS3_GAME/PARAM.SFO");
    if (safe_psf_get_string(psf, "TITLE_ID").empty()) return "{}";
    const bool haveIcon = !iconOut.empty() &&
        copy_game_icon(fs::file{archive.open("PS3_GAME/ICON0.PNG")}, iconOut);
    return sfo_game_info_json(psf, haveIcon);
  } catch (const std::exception &e) {
    emucorec_android.error("probeDiscInfo('%s') failed: %s", path, e.what());
    return "{}";
  }
}

extern "C" std::string emucorec_patchesList(std::string_view serial) {
  patch_engine::patch_map db;
  std::stringstream log;
  patch_engine::load(db, patch_engine::get_patches_path() + "patch.yml", {},
                     false, &log);

  const patch_engine::patch_map enabled = patch_engine::load_config();

  std::string out = "[";
  bool first = true;

  for (const auto &[hash, container] : db) {
    for (const auto &[description, info] : container.patch_info_map) {
      // A patch lists the titles it applies to; a per-game list keeps only the
      // ones naming this serial.
      //
      // Deliberately NOT patch_key::all here. Wildcard patches are keyed by SPU
      // or PPU hash and leave the serial as "All" because the hash is the
      // filter, so they belong to no particular game: the database has 17 of
      // them and listing them under every game buries each game's own handful,
      // while toggling one from a game's list writes the shared "All" entry and
      // changes every other game too. Desktop RPCS3 shows them once, under
      // their own "All titles" node (patch_manager_dialog.cpp), and the global
      // list below (empty serial) is this app's equivalent.
      std::vector<std::pair<std::string, std::string>> targets;

      for (const auto &[title, serials] : info.titles) {
        for (const auto &[ser, versions] : serials) {
          if (!serial.empty() && ser != serial) {
            continue;
          }
          for (const auto &[version, values] : versions) {
            const auto target = std::pair{title, version};
            if (std::find(targets.begin(), targets.end(), target) ==
                targets.end()) {
              targets.push_back(target);
            }
          }
        }
      }

      if (targets.empty()) {
        continue;
      }

      for (const auto &[game_title, app_version] : targets) {
        // Enabled state is specific to this serial AND app version. A wildcard
        // config entry still applies, but a different concrete version does not.
        bool is_on = false;
        if (auto it = enabled.find(hash); it != enabled.end()) {
          if (auto p = it->second.patch_info_map.find(description);
              p != it->second.patch_info_map.end()) {
            for (const auto &[title, serials] : p->second.titles) {
              for (const auto &[ser, versions] : serials) {
                if (!serial.empty() && ser != serial && ser != patch_key::all)
                  continue;
                for (const auto &[ver, values] : versions) {
                  if ((ver == app_version || ver == patch_key::all) &&
                      values.enabled) {
                    is_on = true;
                  }
                }
              }
            }
          }
        }

        if (!first) out += ",";
        first = false;
        out += "{\"hash\":" + json_quote(hash) +
               ",\"name\":" + json_quote(description) +
               ",\"author\":" + json_quote(info.author) +
               ",\"notes\":" + json_quote(info.notes) +
               ",\"version\":" + json_quote(info.patch_version) +
               ",\"appVersion\":" + json_quote(app_version) +
               ",\"game\":" + json_quote(game_title) +
               ",\"enabled\":" + (is_on ? "true" : "false") + "}";
      }
    }
  }

  return out + "]";
}

/** Enable or disable one patch and persist patch_config.yml. */
extern "C" bool emucorec_patchSetEnabled(std::string_view hash,
                                       std::string_view description,
                                       std::string_view serial,
                                       std::string_view appVersion,
                                       bool enabled) {
  patch_engine::patch_map db;
  std::stringstream log;
  patch_engine::load(db, patch_engine::get_patches_path() + "patch.yml", {},
                     false, &log);

  patch_engine::patch_map config = patch_engine::load_config();

  auto h = db.find(std::string(hash));
  if (h == db.end()) {
    emucorec_android.error("patchSetEnabled: unknown hash %s", hash);
    return false;
  }

  auto p = h->second.patch_info_map.find(std::string(description));
  if (p == h->second.patch_info_map.end()) {
    emucorec_android.error("patchSetEnabled: unknown patch %s", description);
    return false;
  }

  // save_config only writes entries whose `enabled` is set, so disabling is
  // simply not carrying the entry over.
  // Start from the current database shape, then carry every previously enabled
  // concrete target across. Replacing it with the database entry directly used
  // to disable version A when the user enabled version B of the same patch.
  auto updated_info = p->second;
  if (auto existing_hash = config.find(std::string(hash));
      existing_hash != config.end()) {
    if (auto existing_patch =
            existing_hash->second.patch_info_map.find(std::string(description));
        existing_patch != existing_hash->second.patch_info_map.end()) {
      for (const auto &[title, serials] : existing_patch->second.titles) {
        for (const auto &[ser, versions] : serials) {
          for (const auto &[ver, values] : versions) {
            if (!values.enabled) continue;
            if (auto title_it = updated_info.titles.find(title);
                title_it != updated_info.titles.end()) {
              if (auto serial_it = title_it->second.find(ser);
                  serial_it != title_it->second.end()) {
                if (auto version_it = serial_it->second.find(ver);
                    version_it != serial_it->second.end()) {
                  version_it->second.enabled = true;
                }
              }
            }
          }
        }
      }
    }
  }

  bool matched = false;
  for (auto &[title, serials] : updated_info.titles) {
    for (auto &[ser, versions] : serials) {
      if (!serial.empty() && ser != serial) continue;
      for (auto &[ver, values] : versions) {
        const bool version_matches =
            appVersion.empty() || ver == appVersion ||
            (appVersion == "all" && ver == patch_key::all);
        if (version_matches) {
          values.enabled = enabled;
          matched = true;
        }
      }
    }
  }

  if (!matched) {
    emucorec_android.error("patchSetEnabled: target not found for %s / %s / %s",
                        description, serial, appVersion);
    return false;
  }

  auto &entry = config[std::string(hash)];
  entry.hash = std::string(hash);
  entry.version = h->second.version;

  auto &info = entry.patch_info_map[std::string(description)];
  info = std::move(updated_info);

  patch_engine::save_config(config);
  return true;
}

// The surface's real size, straight from SurfaceHolder.Callback::surfaceChanged.
//
// Called before the matching surfaceEvent so the renderer never sees a live window it has
// no size for. Zero is ignored rather than stored: a torn-down surface reports 0x0 and
// clearing the size would only make the renderer fall back to guessing again.
extern "C" void emucorec_surfaceSizeChanged(int width, int height) {
  if (width <= 0 || height <= 0) {
    return;
  }

  const u64 packed = (static_cast<u64>(static_cast<u32>(width)) << 32) |
                     static_cast<u32>(height);

  if (g_native_window_size.exchange(packed) != packed) {
    emucorec_android.notice("surface size now %dx%d", width, height);
  }
}

extern "C" bool emucorec_surfaceEvent(JNIEnv *env, jobject surface, jint event) {
    emucorec_android.notice("surface event %p, %d", surface, event);

  if (event == 2) {
    {
      std::lock_guard lock(g_native_window_mutex);
      auto prevWindow = g_native_window.exchange(nullptr);
      if (prevWindow != nullptr) {
        ANativeWindow_release(prevWindow);
        g_native_window_epoch.fetch_add(1);
      }
    }

    // get_pad_thread() defaults to relaxed=false, which is
    // ensure(g_pad_thread.load()) -- it ABORTS on null rather than returning it,
    // so this `if` never got the chance to guard anything. With no game running
    // there is no pad thread, and simply navigating away from the surface (e.g.
    // opening Setup from the library) killed the process.
    //
    // emucorec_openHomeMenu already uses the relaxed form; this call site was
    // missed.
    // surfaceDestroyed runs on the Android UI thread, and both open_home_menu() and
    // Emu.Pause() can block for many seconds during a first-boot bulk PPU precompile (the
    // guest main thread is parked waiting on modules). That froze the UI into an ANR and a
    // force-close whenever someone backgrounded a still-compiling first boot. The native
    // window is already released above -- the only thing the SurfaceHolder contract
    // actually requires -- so hand the rest to a detached worker and return now.
    std::thread([] {
      if (save_states::busy()) return;
      if (auto padThread = pad::get_pad_thread(true)) {
        padThread->open_home_menu();
      }

      // Only pause if the surface is still gone. A quick destroy->recreate (a rotation, a
      // transient focus loss) fires the gained event and resumes; that resume has to win
      // the race against this deferred pause, not lose to it.
      if (g_native_window.load() != nullptr) {
        return;
      }

      // Remember whether this pause is ours. The app pauses for its own reasons too (the
      // in-game overlay), and resuming on the next surface would then undo a pause the user
      // asked for, behind an overlay still showing the game as paused.
      g_paused_by_surface_loss = !Emu.IsPaused();
      Emu.Pause();
    }).detach();

#ifdef EMUCOREC_PGO_GENERATE
    // Backgrounding is the reliable flush point; see pgo_flush.
    pgo_flush();
#endif
  } else {
    // fromSurface hands back a reference that is already acquired, and that is the one
    // stored below. Acquiring on top of it leaked a window per surfaceChanged -- every
    // rotation -- and worse when the surface was unchanged, which is the usual case here
    // since the activity handles orientation itself: fromSurface then returns the SAME
    // window, the pointers match, and the extra reference was not released either.
    auto newWindow = ANativeWindow_fromSurface(env, surface);

    if (newWindow == nullptr) {
      emucorec_android.fatal("returned native window is null, surface %p",
                          surface);
      return false;
    }

    {
      std::lock_guard lock(g_native_window_mutex);
      auto prevWindow = g_native_window.exchange(newWindow);

    if (prevWindow != nullptr) {
      ANativeWindow_release(prevWindow);
    }

    // Only when it really is a different window. Rotation keeps the same one (the activity
    // handles orientation itself), and bumping on every surfaceChanged would rebuild the
    // swapchain for nothing on every rotation.
    if (prevWindow != newWindow) {
      g_native_window_epoch.fetch_add(1);
      emucorec_android.notice("native window replaced (%p -> %p), swapchain will be rebuilt",
                           prevWindow, newWindow);
    }
    }

    if (event == 0 && g_paused_by_surface_loss && Emu.IsPaused()) {
      g_paused_by_surface_loss = false;
      Emu.Resume();
    }
  }

  return true;
}

extern "C" bool emucorec_usbDeviceEvent(int fd, int vendorId, int productId,
                                      int event) {
  emucorec_android.warning(
      "usb device event %d fd: %d, vendorId: %d, productId: %d", event, fd,
      vendorId, productId);

  {
    std::lock_guard lock(g_android_usb_devices_mutex);

    if (event == 0) {
      g_android_usb_devices.push_back({
          .fd = int(fd),
          .vendorId = u16(vendorId),
          .productId = u16(productId),
      });
    } else {
      auto filter = [fd](auto device) { return device.fd == fd; };
      if (auto it = std::ranges::find_if(g_android_usb_devices, filter);
          it != g_android_usb_devices.end()) {
        g_android_usb_devices.erase(it);
      }
    }
  }

  {
    auto selectedHandler = g_cfg_input.player1.handler.get();
    std::string selectedDevice;

    std::map<pad_handler, std::pair<std::unique_ptr<PadHandlerBase>,
                                    std::vector<std::string>>>
        handlerToDevices;

    auto collectDevices = [&]<typename T>(T handler) {
      handler->Init();

      std::vector<std::string> devices;
      for (const auto &device : handler->list_devices()) {
        devices.push_back(device.name);
      }

      auto type = handler->m_type;

      handlerToDevices[type] = std::pair{
          std::move(handler),
          std::move(devices),
      };
    };

    collectDevices(std::make_unique<dualsense_pad_handler>());
    collectDevices(std::make_unique<ds4_pad_handler>());
    collectDevices(std::make_unique<ds3_pad_handler>());

    if (handlerToDevices[selectedHandler].second.empty()) {
      selectedHandler = pad_handler::null;
    }

    if (!handlerToDevices[pad_handler::dualsense].second.empty()) {
      selectedHandler = pad_handler::dualsense;
    } else if (!handlerToDevices[pad_handler::ds4].second.empty()) {
      selectedHandler = pad_handler::ds4;
    } else if (!handlerToDevices[pad_handler::ds3].second.empty()) {
      selectedHandler = pad_handler::ds3;
    }

    if (selectedHandler == pad_handler::null) {
      selectedHandler = pad_handler::virtual_pad;
    }

    // Ports 2-7 are always virtual. The probe above only ever assigns player 1,
    // so without this every extra controller the app routed had no pad on the
    // other side and simply did nothing.
    //
    // Deliberately OUTSIDE the "did player 1's handler change?" branch below:
    // once player 1 is saved as virtual_pad, that branch stops running on every
    // later boot, and ports 2-7 were left Null forever -- which is exactly what
    // the pad log showed (Pad 0 Virtual, Pads 1-6 Null).
    //
    // The app is the input source for them either way: it reads Android input
    // events itself and pushes whole-pad snapshots through emucorec_overlayPadData,
    // so a core-side HID handler is not wanted here.
    bool extra_ports_changed = false;
    for (usz i = 1; i < g_cfg_input.player.size(); i++) {
      if (g_cfg_input.player[i]->handler.get() != pad_handler::virtual_pad) {
        g_cfg_input.player[i]->handler.set(pad_handler::virtual_pad);
        g_cfg_input.player[i]->device.from_string("Virtual");
        extra_ports_changed = true;
      }
    }

    if (extra_ports_changed) {
      g_cfg_input.save("", g_cfg_input_configs.default_config);

      if (!Emu.IsStopped()) {
        pad::reset(Emu.GetTitleID());
      }
    }

    if (selectedHandler != g_cfg_input.player1.handler.get()) {
      emucorec_android.notice("install %s pad handler", selectedHandler);

      g_cfg_input.player1.handler.set(selectedHandler);

      if (selectedHandler == pad_handler::null) {
        g_cfg_input.player1.device.from_default();
      } else if (selectedHandler == pad_handler::virtual_pad) {
        g_cfg_input.player1.handler.set(pad_handler::virtual_pad);
        g_cfg_input.player1.device.from_string("Virtual");
      } else {
        g_cfg_input.player1.device.from_string(
            handlerToDevices[selectedHandler].second.front());
        handlerToDevices[selectedHandler].first->init_config(
            &g_cfg_input.player1.config);
        if (selectedHandler != pad_handler::virtual_pad) {
          std::lock_guard lock(g_virtual_pad_mutex);
          g_virtual_pads[0] = nullptr;
        }
      }

      g_cfg_input.save("", g_cfg_input_configs.default_config);

      if (!Emu.IsStopped()) {
        pad::reset(Emu.GetTitleID());
      }
    }
  }

  return true;
}

static bool runStoppedInstall(JNIEnv *env, jlong progressId,
                              const std::function<bool()> &install) {
  std::lock_guard lifecycle_lock(g_emu_lifecycle_mutex);
  try {
    if (save_states::busy()) {
      Progress(env, progressId).failure("Wait for the save state operation to finish");
      return false;
    }
    if (!Emu.IsStopped(true)) {
      Progress(env, progressId).failure("Stop the current game before installing");
      return false;
    }
    // A completed boot or precompile tears down the VFS and JIT. Restore the
    // configured mounts before TAR/PKG extraction, including on reinstall.
    Emu.Init();
    return install();
  } catch (const std::exception &error) {
    emucorec_android.error("Installation failed: %s", error.what());
    Progress(env, progressId).failure("Installation failed");
    return false;
  }
}

static bool installPup(JNIEnv *env, fs::file &&pup_f, jlong progressId) {
  Progress progress(env, progressId);

  pup_object pup(std::move(pup_f));
  AtExit atExit{[&] { pup.file().release_handle(); }};

  if (static_cast<pup_error>(pup) == pup_error::hash_mismatch) {
    emucorec_android.fatal("installFw: invalid PUP");
    progress.failure("Selected file is not firmware update file");
    return false;
  }

  if (static_cast<pup_error>(pup) != pup_error::ok) {
    emucorec_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  fs::file update_files_f = pup.get_file(0x300);

  const usz update_files_size = update_files_f ? update_files_f.size() : 0;

  if (!update_files_size) {
    emucorec_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  tar_object update_files(update_files_f);

  auto update_filenames = update_files.get_filenames();
  update_filenames.erase(std::remove_if(update_filenames.begin(),
                                        update_filenames.end(),
                                        [](const std::string &s) {
                                          return !s.starts_with("dev_flash_");
                                        }),
                         update_filenames.end());

  if (update_filenames.empty()) {
    emucorec_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  std::string version_string;

  if (fs::file version = pup.get_file(0x100)) {
    version_string = version.to_string();
  }

  if (const usz version_pos = version_string.find('\n');
      version_pos != std::string::npos) {
    version_string.erase(version_pos);
  }

  if (version_string.empty()) {
    emucorec_android.fatal("installFw: invalid PUP");
    progress.failure("Firmware update file is broken");
    return false;
  }

  jlong processed = 0;
  for (const auto &update_filename : update_filenames) {
    auto update_file_stream = update_files.get_file(update_filename);

    if (update_file_stream->m_file_handler) {
      // Forcefully read all the data
      update_file_stream->m_file_handler->handle_file_op(
          *update_file_stream, 0, update_file_stream->get_size(umax), nullptr);
    }

    fs::file update_file = fs::make_stream(std::move(update_file_stream->data));

    SCEDecrypter self_dec(update_file);
    self_dec.LoadHeaders();
    self_dec.LoadMetadata(SCEPKG_ERK, SCEPKG_RIV);
    self_dec.DecryptData();

    auto dev_flash_tar_f = self_dec.MakeFile();

    if (dev_flash_tar_f.size() < 3) {
      emucorec_android.error(
          "Firmware installation failed: Firmware could not be decompressed");

      progress.failure("Firmware update file could not be decompressed");
      return false;
    }

    tar_object dev_flash_tar(dev_flash_tar_f[2]);

    if (!dev_flash_tar.extract()) {

      emucorec_android.error("Error while installing firmware: TAR contents are "
                          "invalid. (package=%s)",
                          update_filename);

      progress.failure(fmt::format("TAR contents are invalid (package=%s)",
                                   update_filename));
      return false;
    }

    if (!progress.report(processed++, update_filenames.size())) {
      // Installation was cancelled
      return false;
    }
  }

  sendFirmwareInstalled(env, utils::get_firmware_version());

  // The install is DONE once the PUP contents are on disk. Building the VSH
  // PPU cache is a background optimization for the first system-menu boot, not
  // part of installing: it used to share this install's progress id, so the
  // onboarding dialog sat on a finished extraction for minutes while vsh.self
  // compiled. It now runs detached, and FirmwareState only flips to Compiled
  // when onFirmwareCompiled arrives.
  progress.success(0);
  g_compilationQueue.pushDetached(g_cfg_vfs.get_dev_flash() +
                                  "/vsh/module/vsh.self");
  return true;
}

// Install one or more PKG files as a single package.
//
// A split release ships as .pkg_0/.pkg_1/... and only installs correctly when every
// part is handed to package_reader::extract_data TOGETHER -- which already takes a
// deque and has always supported this. Only the entry point was single-file, so a
// split game could not be installed at all. Parts must arrive in order; the caller
// sorts them by filename.
static bool installPkg(JNIEnv *env, std::vector<fs::file> &&files,
                       jlong progressId) {
  Progress progress(env, progressId);

  if (files.empty()) {
    progress.failure("No package files selected");
    return false;
  }

  std::deque<package_reader> readers;
  std::deque<std::string> bootable_paths;
  for (std::size_t i = 0; i < files.size(); ++i) {
    readers.emplace_back(fmt::format("part%u.pkg", i), std::move(files[i]));
  }

  AtExit atExit{[&] {
    for (auto &reader : readers) {
      reader.file().release_handle();
    }
  }};

  package_install_result result = {};
  named_thread worker("PKG Installer", [&readers, &result, &bootable_paths] {
    result = package_reader::extract_data(readers, bootable_paths, false);
    return result.error == package_install_result::error_type::no_error;
  });

  const jlong maxProgress = 10000;

  while (true) {
    std::uint64_t totalProgress = 0;
    for (auto &reader : readers) {
      if (result.error != package_install_result::error_type::no_error) {
        progress.failure("Installation failed");
        for (package_reader &reader : readers) {
          reader.abort_extract();
        }
        return false;
      }

      totalProgress += reader.get_progress(maxProgress);
    }

    if (totalProgress == maxProgress * readers.size()) {
      break;
    }

    totalProgress /= readers.size();

    if (!progress.report(totalProgress, maxProgress)) {
      for (package_reader &reader : readers) {
        reader.abort_extract();
      }

      return false;
    }

    std::this_thread::sleep_for(std::chrono::seconds(2));
  }

  if (!worker()) {
    progress.failure("Installation failed");
    return false;
  }

  // Deliberately NOT queued for precompilation.
  //
  // CompilationQueue::impl does Emu.SetState(running), g_fxo->init<>() of the
  // progress-dialog server and main_ppu_module, and vm::init(). That is only safe in
  // a process that has not already brought those up -- which is true during onboarding
  // (firmware install) and false for a PKG installed from the library after a game has
  // been booted. init<> returns null the second time and vm::init() re-maps an address
  // space that already exists, which crashed the app the moment extraction finished.
  // The extraction itself had already completed, which is why the game still appeared
  // in the library on the next launch.
  //
  // Nothing is lost by skipping it: the game precompiles on its first boot, with the
  // normal compile screen, exactly as a disc game does. RPCS3 desktop does not
  // precompile on install either.

  progress.success(0);
  return true;
}

extern "C" void emucorec_displayRefreshRateChanged(double refreshRate) {
  if (!std::isfinite(refreshRate) || refreshRate < 20.0 || refreshRate > 1000.0) {
    return;
  }

  g_display_refresh_millihz.store(
      static_cast<u32>(std::lround(refreshRate * 1000.0)));
}

static bool installEdat(JNIEnv *env, fs::file &&file, jlong progressId,
                        std::string_view rootPath = {}) {
  Progress progress(env, progressId);

  NPD_HEADER npdHeader;
  if (!file.read(npdHeader)) {
    progress.failure("Invalid EDAT file");
    return false;
  }

  if (!rootPath.empty()) {
    auto ebootPath = locateEbootPath(rootPath);
    auto sfoPath = locateParamSfoPath(rootPath);

    if (sfoPath.empty()) {
      progress.failure("Game is broken: PARAM.SFO not found");
      return false;
    }

    auto psf = psf::load_object(sfoPath);
    auto contentId = safe_psf_get_string(psf, "CONTENT_ID");

    if (contentId != npdHeader.content_id) {
      progress.failure(fmt::format("File cannot be used for this game. EDAT "
                                   "content ID missmatch %s vs %s",
                                   contentId, npdHeader.content_id));
      return false;
    }
  }

  const auto licenseFile =
      fmt::format("%shome/%s/exdata/%s.edat", rpcs3::utils::get_hdd0_dir(),
                  Emu.GetUsr(), npdHeader.content_id);

  file.seek(0);

  std::vector<std::uint8_t> bytes(file.size());
  if (!file.read(bytes)) {
    progress.failure("Failed to read key");
    return false;
  }

  if (!fs::write_file(licenseFile, fs::open_mode::create + fs::open_mode::trunc,
                      bytes)) {
    progress.failure(fmt::format("Failed to write EDAT to %s", licenseFile));
    return false;
  }

  return true;
}

static bool installRap(JNIEnv *env, fs::file &&file, jlong progressId,
                       std::string_view rootPath) {
  Progress progress(env, progressId);

  auto ebootPath = locateEbootPath(rootPath);

  std::vector<std::uint8_t> bytes;
  if (!file.read(bytes, 16)) {
    progress.failure("Failed to read key");
    return false;
  }

  SelfAdditionalInfo info;
  decrypt_self(fs::file(ebootPath), nullptr, &info);

  auto npd = [&]() -> NPD_HEADER * {
    for (auto &supplemental : info.supplemental_hdr) {
      if (supplemental.type == 3) {
        return &supplemental.PS3_npdrm_header.npd;
      }
    }

    return nullptr;
  }();

  if (npd == nullptr) {
    progress.failure("Failed to fetch NPDRM of SELF");
    return false;
  }

  const auto licenseFile =
      fmt::format("%shome/%s/exdata/%s.rap", rpcs3::utils::get_hdd0_dir(),
                  Emu.GetUsr(), npd->content_id);

  if (!fs::write_file(licenseFile, fs::open_mode::create + fs::open_mode::trunc,
                      bytes)) {
    progress.failure(fmt::format("Failed to write key to %s", licenseFile));
    return false;
  }

  if (!decrypt_self(fs::file(ebootPath))) {
    progress.failure("Provided key is invalid for selected game");
    fs::remove_file(licenseFile);
    return false;
  }

  g_compilationQueue.push(progress, std::move(ebootPath));
  return true;
}

// Minimal ISO9660 reader over an already-open file handle. The modern core's
// iso_archive is path-based, but the install API only receives a descriptor,
// so the disc layout is parsed directly from it. Plain ISO9660 (the layout PS3
// discs ship with); names are matched case-insensitively.
class Iso9660Reader {
public:
  struct Entry {
    u64 extent = 0; // byte offset into the image
    u64 size = 0;
    bool isDir = false;
    bool multiExtent = false; // file continues in the next record
    std::string name; // without the ";1" version suffix
  };

  explicit Iso9660Reader(fs::file &file) : m_file(file) {}

  // Parse a directory record starting at `offset` (record layout is fixed:
  // length, extended attribute length, extent (LE), extent (BE), size (LE),
  // size (BE), date, flags, ..., name length, name).
  bool readRecord(u64 offset, Entry &entry) {
    u8 buf[70];
    m_file.seek(offset);
    u8 len = 0;
    if (!m_file.read(&len, 1)) {
      return false;
    }
    if (len == 0 || len > sizeof(buf)) {
      return false;
    }
    if (!m_file.read(buf, len - 1)) {
      return false;


    }

    entry.extent =
        u64(u32(buf[1]) | u32(buf[2]) << 8 | u32(buf[3]) << 16 | u32(buf[4]) << 24) *
        2048;
    entry.size = u32(buf[9]) | u32(buf[10]) << 8 | u32(buf[11]) << 16 |
                 u32(buf[12]) << 24;
    entry.isDir = (buf[24] & 0x02) != 0;
    entry.multiExtent = (buf[24] & 0x80) != 0;
    entry.name.assign(reinterpret_cast<const char *>(buf) + 32, buf[31]);
    if (auto semi = entry.name.find(';'); semi != std::string::npos) {
      entry.name.erase(semi);
    }
    return true;
  }

  // List the entries of a directory. In ISO9660 the "." and ".." links are
  // single-byte names 0x00 and 0x01 (not the ASCII strings), and treating the
  // parent link as a real subdirectory points back into the tree, so a disc
  // with such a record would make the walk recurse forever and exhaust the
  // heap. Guard the record count too: directory sizes come from the image and
  // a corrupt value would otherwise walk gigabytes of garbage.
  bool listDir(const Entry &dir, std::vector<Entry> &out) {
    out.clear();
    const u64 end = dir.extent + dir.size;
    for (u64 off = dir.extent; off < end;) {
      m_file.seek(off);
      u8 len = 0;
      if (!m_file.read(&len, 1)) {
        return false;
      }
      if (len == 0) {
        off = (off / 2048 + 1) * 2048; // skip inter-record padding
        continue;
      }
      Entry entry;
      if (!readRecord(off, entry)) {
        return false;
      }
      off += len;
      if (entry.name.empty()) {
        continue;
      }
      const bool is_self = entry.name == "." ||
                           (entry.name.size() == 1 && u8(entry.name[0]) == 0);
      const bool is_parent = entry.name == ".." ||
                             (entry.name.size() == 1 && u8(entry.name[0]) == 1);
      if (is_self || is_parent) {
        continue;
      }
      if (out.size() >= 100000) {
        emucorec_android.warning("iso: refusing directory with too many records");
        return false;
      }
      out.emplace_back(std::move(entry));
    }
    return true;
  }

  // The root directory record from the primary volume descriptor.
  bool rootDir(Entry &dir) {
    u8 pvd[2048];
    m_file.seek(0x8000);
    if (!m_file.read(pvd, sizeof(pvd)) || pvd[0] != 1) {
      return false;
    }
    if (!readRecord(0x8000 + 156, dir) || !dir.isDir) {
      return false;
    }
    return true;
  }

  // Locate a file by its ISO path, e.g. "PS3_GAME/PARAM.SFO".
  bool find(const std::string &path, Entry &out) {
    Entry dir;
    if (!rootDir(dir)) {
      return false;
    }

    std::vector<std::string> parts;
    std::string current;
    for (const char c : path) {
      if (c == '/') {
        if (!current.empty()) {
          parts.emplace_back(std::move(current));
          current.clear();
        }
      } else {
        current.push_back(c);
      }
    }
    if (!current.empty()) {
      parts.emplace_back(std::move(current));
    }

    auto equalsIgnoreCase = [](const std::string &a, const std::string &b) {
      if (a.size() != b.size()) {
        return false;
      }
      for (std::size_t i = 0; i < a.size(); ++i) {
        if (std::tolower(static_cast<u8>(a[i])) !=
            std::tolower(static_cast<u8>(b[i]))) {
          return false;
        }
      }
      return true;
    };

    for (auto it = parts.begin(); it != parts.end(); ++it) {
      std::vector<Entry> entries;
      if (!listDir(dir, entries)) {
        return false;
      }
      auto found = std::find_if(entries.begin(), entries.end(),
                                [&](const Entry &entry) {
                                  return equalsIgnoreCase(entry.name, *it);
                                });
      if (found == entries.end()) {
        return false;
      }
      out = std::move(*found);
      if (it + 1 != parts.end()) {
        if (!out.isDir) {
          return false;
        }
        dir = out;
      }
    }
    return true;
  }

private:
  fs::file &m_file;
};

static bool installIso(JNIEnv *env, fs::file &&file, jlong progressId) {
  Progress progress(env, progressId);

  Iso9660Reader iso(file);

  Iso9660Reader::Entry sfoEntry;
  if (!iso.find("PS3_GAME/PARAM.SFO", sfoEntry)) {
    progress.failure("Failed to locate PARAM.SFO in ISO");
    return false;
  }

  if (sfoEntry.size == 0 || sfoEntry.size > 4 * 1024 * 1024) {
    progress.failure("Invalid PARAM.SFO record in ISO");
    return false;
  }

  file.seek(sfoEntry.extent);
  std::vector<u8> sfoData(sfoEntry.size);
  if (!file.read(sfoData.data(), sfoData.size())) {
    progress.failure("Failed to read PARAM.SFO from ISO");
    return false;
  }

  auto sfoFile = fs::make_stream<std::vector<u8>>(std::move(sfoData));
  auto sfo = psf::load_object(sfoFile, "iso://PS3_GAME/PARAM.SFO");
  auto titleId = safe_psf_get_string(sfo, "TITLE_ID");

  if (titleId.empty()) {
    progress.failure("Failed to fetch TITLE_ID from PARAM.SFO in ISO");
    return false;
  }

  std::filesystem::path destinationPath =
      fs::get_config_dir() + "games/" + std::string(titleId);

  // Enumerate the whole tree (PS3_UPDATE is not part of the playable game).
  // Files are stored as an extent list: ISO9660 splits files larger than the
  // first extent into consecutive records carrying the same name, and those
  // must be written back to back or the image data is corrupt.
  std::vector<std::string> dirs;
  std::vector<std::pair<std::string, std::vector<Iso9660Reader::Entry>>> files;
  u64 totalBytes = 0;

  {
    Iso9660Reader::Entry root;
    if (!iso.rootDir(root)) {
      progress.failure("Failed to read the ISO directory tree");
      return false;
    }

    std::unordered_set<u64> seenDirs{root.extent};
    std::vector<std::pair<std::string, Iso9660Reader::Entry>> workList;
    workList.emplace_back("", std::move(root));

    while (!workList.empty()) {
      auto [isoPath, entry] = std::move(workList.back());
      workList.pop_back();

      std::vector<Iso9660Reader::Entry> entries;
      if (!iso.listDir(entry, entries)) {
        progress.failure(fmt::format("Failed to read directory %s in ISO",
                                     isoPath.empty() ? "/" : isoPath));
        return false;
      }

      for (auto &child : entries) {
        if (isoPath.empty() && child.name == "PS3_UPDATE") {
          continue;
        }
        auto childPath =
            isoPath.empty() ? child.name : isoPath + "/" + child.name;
        if (child.isDir) {
          if (dirs.size() >= 10000 || !seenDirs.emplace(child.extent).second) {
            continue; // cycle or absurd tree: stop descending
          }
          dirs.emplace_back(childPath);
          workList.emplace_back(std::move(childPath), std::move(child));
        } else {
          totalBytes += child.size;
          if (!files.empty() && files.back().first == childPath) {
            files.back().second.emplace_back(std::move(child));
          } else {
            files.emplace_back(childPath,
                               std::vector<Iso9660Reader::Entry>{
                                   std::move(child)});
          }
          if (files.size() >= 100000) {
            progress.failure("ISO game has too many files");
            return false;
          }
        }
      }
    }
  }

  emucorec_android.notice("installIso: %s: %zu dirs, %zu files, %llu bytes",
                       titleId, dirs.size(), files.size(), totalBytes);

  std::error_code ec;
  if (!std::filesystem::create_directories(destinationPath, ec) && ec) {
    progress.failure(fmt::format("Failed to create dir %s: %s",
                                 destinationPath.string(), ec.message()));
    return false;
  }

  for (auto &dirPath : dirs) {
    auto dest = destinationPath / dirPath;
    std::filesystem::create_directories(dest, ec);
    if (ec) {
      progress.failure(fmt::format("Failed to create dir %s: %s",
                                   dest.string(), ec.message()));
      return false;
    }
  }

  progress.report(0, totalBytes);

  std::vector<u8> buffer(4 * 1024 * 1024);
  u64 processedBytes = 0;

  for (auto &[isoPath, parts] : files) {
    auto dest = destinationPath / isoPath;
    fs::file out;
    if (!out.open(dest.string(),
                  fs::open_mode::create + fs::open_mode::trunc +
                      fs::open_mode::write)) {
      progress.failure(fmt::format("Failed to write file: %s", dest.string()));
      return false;
    }

    for (auto &part : parts) {
      file.seek(part.extent);
      u64 remaining = part.size;
      while (remaining) {
        const u64 chunk = std::min<u64>(buffer.size(), remaining);
        if (!file.read(buffer.data(), chunk)) {
          progress.failure(
              fmt::format("Failed to read %s in ISO", isoPath));
          return false;
        }
        if (!out.write(buffer.data(), chunk)) {
          progress.failure(
              fmt::format("Failed to write file: %s", dest.string()));
          return false;
        }
        remaining -= chunk;
        processedBytes += chunk;
      }
    }

    progress.report(processedBytes, totalBytes);
  }

  progress.success(processedBytes);
  return true;
}

extern "C" bool emucorec_installFw(JNIEnv *env, int fd, long progressId) {
  return runStoppedInstall(env, progressId, [&] {
    return installPup(env, fs::file::from_native_handle(fd), progressId);
  });
}

// Install a set of PKG parts as one package. All parts must be PKGs, in order.
// Single-file installs keep going through emucorec_install, which also handles
// PUP/EDAT/ISO by sniffing the file type.
extern "C" bool emucorec_installSplitPkg(JNIEnv *env, const int *fds, int count,
                                       long progressId) {
  return runStoppedInstall(env, progressId, [&] {
    if (fds == nullptr || count <= 0) {
      Progress(env, progressId).failure("No package files selected");
      return false;
    }

    std::vector<fs::file> files;
    files.reserve(count);
    for (int i = 0; i < count; ++i) {
      files.push_back(fs::file::from_native_handle(fds[i]));
    }

    // The Java side owns every fd; release each handle so closing the
    // ParcelFileDescriptors there is not a double close.
    AtExit atExit{[&] {
      for (auto &f : files) {
        f.release_handle();
      }
    }};

    for (auto &f : files) {
      if (getFileType(f) != FileType::Pkg) {
        Progress(env, progressId)
            .failure("Every selected file must be a .pkg part");
        return false;
      }
      f.seek(0);
    }

    return installPkg(env, std::move(files), progressId);
  });
}

// Delete an installed title's directory (dev_hdd0/game/<TITLEID> and its
// matching save-data-free content). Path comes from the app, which only offers
// this for games it found under the emulator's own game directories.
extern "C" bool emucorec_uninstallGame(std::string_view path) {
  if (path.empty()) {
    return false;
  }

  const std::string dir(path);

  // Refuse anything that is not inside the emulator's own game storage, so a
  // mis-sent path cannot delete a user's ROM folder.
  const std::string hdd0 = rpcs3::utils::get_hdd0_dir();
  const std::string games = hdd0 + "game/";
  if (!dir.starts_with(games)) {
    emucorec_android.error("uninstallGame: refusing path outside %s: %s", games,
                        dir);
    return false;
  }

  if (!fs::is_dir(dir)) {
    return false;
  }

  emucorec_android.notice("uninstallGame: removing %s", dir);
  return fs::remove_all(dir);
}

extern "C" bool emucorec_install(JNIEnv *env, int fd, long progressId) {
  return runStoppedInstall(env, progressId, [&] {
    auto file = fs::file::from_native_handle(fd);
    AtExit atExit{[&] { file.release_handle(); }};

    auto type = getFileType(file);
    file.seek(0);

    switch (type) {
    case FileType::Unknown:
      Progress(env, progressId).failure("Unsupported file type");
      return false;

    case FileType::Pup:
      return installPup(env, std::move(file), progressId);

    case FileType::Pkg: {
      std::vector<fs::file> files;
      files.push_back(std::move(file));
      return installPkg(env, std::move(files), progressId);
    }

    case FileType::Edat:
      return installEdat(env, std::move(file), progressId);

    case FileType::Iso:
      return installIso(env, std::move(file), progressId);

    case FileType::Rap:
      Progress(env, progressId)
          .failure("RAP file cannot be preinstalled. Use lock button on "
                   "installed game instead");
      return false;
    }

    return true;
  });
}

extern "C" bool emucorec_installKey(JNIEnv *env, int fd, long progressId,
                                  std::string_view gamePath) {
  return runStoppedInstall(env, progressId, [&] {
    auto file = fs::file::from_native_handle(fd);
    AtExit atExit{[&] { file.release_handle(); }};

    auto type = getFileType(file);
    file.seek(0);

    if (type == FileType::Rap) {
      return installRap(env, std::move(file), progressId, gamePath);
    }

    if (type == FileType::Edat) {
      return installEdat(env, std::move(file), progressId, gamePath);
    }

    Progress(env, progressId).failure("Unsupported key type");
    return false;
  });
}

extern "C" std::string emucorec_systemInfo() {
  std::string result;

  // LLVM CPU reports the same resolution path the JIT actually uses (configured
  // CPU -> LLVM host detection -> project fallback), not just the fallback guess.
  fmt::append(result, "SoC: %s\n\n%s\n\nLLVM CPU: %s\n\n",
              g_android_soc_info.empty() ? "unknown" : g_android_soc_info,
              utils::get_system_info(),
              jit_compiler::cpu(g_cfg.core.llvm_cpu.to_string()));

  {
    vk::instance device_enum_context;
    if (device_enum_context.create("RPCS3")) {
      device_enum_context.bind();
      const std::vector<vk::physical_device> &gpus =
          device_enum_context.enumerate_devices();

      for (const auto &gpu : gpus) {
        // Upstream has no get_driver_name()/get_driver_vk_version(); the
        // vendor enum plus the driver version string is what it exposes.
        fmt::append(result, "GPU: %s\n\nDriver: v%s",
                    gpu.get_name(), gpu.get_driver_version());
      }
    }
  }

  return result;
}

static cfg::_base *find_cfg_node(cfg::_base *root, std::string_view path) {
  auto pathList = fmt::split(path, {"@@"});
  std::ranges::reverse(pathList);

  while (!pathList.empty()) {
    auto elem = pathList.back();
    pathList.pop_back();
    if (elem.empty()) {
      continue;
    }

    auto root_node = dynamic_cast<cfg::node *>(root);
    if (root_node == nullptr) {
      return nullptr;
    }

    cfg::_base *child_node = nullptr;

    for (auto node : root_node->get_nodes()) {
      if (node->get_name() == elem) {
        child_node = node;
        break;
      }
    }

    if (child_node == nullptr) {
      return nullptr;
    }

    root = child_node;
  }

  return root;
}

extern "C" void emucorec_loginUser(std::string_view userId) {
  Emu.SetUsr(std::string(userId));
}

extern "C" std::string emucorec_getUser() { return Emu.GetUsr(); }

// ---------------------------------------------------------------------------
// Settings bridge.
//
// The Compose settings screen is GENERATED from this JSON -- it is not a
// hand-written list of screens. That is how all ~281 entries of RPCS3's config
// tree (Core, VFS, Video/Vulkan/Perf-Overlay, Audio, Input/Output, System, Net,
// Savestate, Miscellaneous) show up without anyone maintaining a UI per knob.
// Break the shape below and the entire settings UI silently empties out.
//
// Contract expected by ui/settings/SettingsScreen.kt:
//   node  -> { "<child name>": <recurse>, ... }          (no "type" key)
//   leaf  -> { "type": "bool"|"enum"|"int"|"uint"|"float",
//              "value": <bool | string>,
//              "default": <bool | string>,
//              "variants": [ ... ]     // enum only
//              "min": "..", "max": ".." // numerics only
//            }
//
// The original port did this by adding to_json/from_json virtuals to
// Utilities/Config.h and pulling in nlohmann. We keep the core clean instead:
// the only upstream
// addition is min_to_string()/max_to_string() on cfg::_base, because _int/uint/
// _float carry Min/Max as static constexpr TEMPLATE parameters that a _base*
// cannot otherwise reach -- without them every numeric renders as a text box
// instead of a slider.
// ---------------------------------------------------------------------------

static void json_append_escaped(std::string &out, std::string_view value) {
  out += '"';
  for (char c : value) {
    switch (c) {
    case '"': out += "\\\""; break;
    case '\\': out += "\\\\"; break;
    case '\n': out += "\\n"; break;
    case '\r': out += "\\r"; break;
    case '\t': out += "\\t"; break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        fmt::append(out, "\\u%04x", static_cast<unsigned>(c) & 0xff);
      } else {
        out += c;
      }
    }
  }
  out += '"';
}

// cfg::_float reports itself as cfg::type::_int (see its ctor), so the enum
// alone cannot separate float from integer. The range strings can: std::to_string
// on a floating point type always yields a decimal point.
static bool cfg_is_float(const cfg::_base *node) {
  // EmuCoreC: modern cfg::_base has no min/max accessors; detect floats by the value.
  const std::string value = node->to_string();
  return value.find('.') != std::string::npos;
}

static const char *cfg_type_name(const cfg::_base *node) {
  switch (node->get_type()) {
  case cfg::type::_bool: return "bool";
  case cfg::type::_enum: return "enum";
  case cfg::type::_int: return cfg_is_float(node) ? "float" : "int";
  case cfg::type::uint: return "uint";
  default: return "string";
  }
}

static void emit_cfg_json(const cfg::_base *node, std::string &out) {
  if (node->get_type() == cfg::type::node) {
    out += '{';
    bool first = true;
    for (const auto *child : static_cast<const cfg::node *>(node)->get_nodes()) {
      if (!first) out += ',';
      first = false;
      json_append_escaped(out, child->get_name());
      out += ':';
      emit_cfg_json(child, out);
    }
    out += '}';
    return;
  }

  const bool is_bool = node->get_type() == cfg::type::_bool;

  out += "{\"type\":";
  json_append_escaped(out, cfg_type_name(node));

  // Booleans go out as real JSON booleans -- the Kotlin reads them with
  // getBoolean(), which throws on a quoted string.
  out += ",\"value\":";
  if (is_bool) {
    out += (node->to_string() == "true") ? "true" : "false";
  } else {
    json_append_escaped(out, node->to_string());
  }

  out += ",\"default\":";
  if (is_bool) {
    out += (node->def_to_string() == "true") ? "true" : "false";
  } else {
    json_append_escaped(out, node->def_to_string());
  }

  if (node->get_type() == cfg::type::_enum) {
    out += ",\"variants\":[";
    bool first = true;
    for (const std::string &variant : node->to_list()) {
#ifndef RSX_GLES
      if (node == &g_cfg.video.renderer && variant == "OpenGL") {
        continue;
      }
#endif
      if (!first) out += ',';
      first = false;
      json_append_escaped(out, variant);
    }
    out += ']';
  }

  // Numeric entries expose their range so the app can render sliders.
  // Note: cfg::_float registers as cfg::type::_int (upstream quirk), which is
  // exactly the set of types that carry a numeric range here.
  if (node->get_type() == cfg::type::_int || node->get_type() == cfg::type::uint) {
    out += ",\"min\":";
    out += std::to_string(node->get_min());
    out += ",\"max\":";
    out += std::to_string(node->get_max());
  }

  out += '}';
}

extern "C" std::string emucorec_settingsGet(std::string_view path) {
  auto root = find_cfg_node(&g_cfg, path);

  if (root == nullptr) {
    return {};
  }

  std::string out;
  out.reserve(64 * 1024);
  emit_cfg_json(root, out);
  return out;
}

// Defined in Emu/RSX/Overlays/overlay_perf_metrics.cpp and overlay_debug_overlay.cpp;
// upstream declares them the same way in main_application.cpp.
namespace rsx::overlays {
extern void reset_performance_overlay();
extern void reset_debug_overlay();
extern void set_app_info(std::string version, std::string build, std::string device);
} // namespace rsx::overlays

// Settings batching.
//
// Every settingsSet used to end in Emulator::SaveSettings(g_cfg.to_string(), ""), which
// serialises the WHOLE PS3 config to YAML and writes it to disk. The app pushes its full
// settings block on each change -- ~165 keys -- so one toggle in the in-game menu cost 165
// whole-config serialisations and 165 file writes, synchronously, on the UI thread. That is
// the reported "lag when switching settings in the in game menu".
//
// Between begin/end the save is deferred and coalesced into exactly one write. Single
// settingsSet calls (the dynamic settings screen) are unaffected and still save immediately.
static std::atomic<int> g_settings_batch_depth{0};
static std::mutex g_global_settings_mutex;
static std::vector<std::pair<std::string, std::string>> g_global_settings_edits;
static std::mutex g_game_settings_mutex;
static std::vector<std::pair<std::string, std::string>> g_pending_game_settings;

extern "C" void emucorec_clearPendingGameSettings() {
  std::lock_guard lock(g_game_settings_mutex);
  g_pending_game_settings.clear();
}

// Emulator::Init reloads global settings, then Load applies the title database.
// Replay the explicit game profile after both layers have been applied.
extern "C" void emucorec_apply_pending_game_settings() {
  std::vector<std::pair<std::string, std::string>> settings;
  {
    std::lock_guard lock(g_game_settings_mutex);
    settings.swap(g_pending_game_settings);
  }
  for (const auto &[path, value] : settings) {
    auto *node = find_cfg_node(&g_cfg, path);
    if (!node || !node->from_string(value)) {
      emucorec_android.error("Could not apply game setting %s", path);
    }
  }
}

// Called with g_global_settings_mutex held. Apply only explicit global edits to
// the disk configuration; the live tree can contain a game's overrides.
static bool write_global_settings_edits() {
  if (g_global_settings_edits.empty()) return true;
  const std::string path = fs::get_config_dir(true) + "config.yml";
  auto saved = std::make_unique<cfg_root>();
  saved->from_default();
  if (const fs::file file{path}) {
    if (!saved->from_string(file.to_string())) {
      emucorec_android.error("settingsSet: cannot parse %s; preserving it", path);
      return false;
    }
  } else if (fs::is_file(path)) {
    emucorec_android.error("settingsSet: cannot read %s; preserving it", path);
    return false;
  } else {
    saved->video.renderer.set(g_cfg.video.renderer.get());
    saved->video.vk.adapter.from_string(g_cfg.video.vk.adapter.to_string());
  }
  for (const auto &[node_path, value] : g_global_settings_edits) {
    auto *node = find_cfg_node(saved.get(), node_path);
    if (!node || !node->from_string(value)) {
      emucorec_android.error("settingsSet: cannot persist %s", node_path);
      return false;
    }
  }
  if (!Emulator::SaveSettingsFile(saved->to_string(), "")) return false;
  g_global_settings_edits.clear();
  g_backup_cfg.from_string(g_cfg.to_string());
  return true;
}

extern "C" void emucorec_settingsBeginBatch() { g_settings_batch_depth.fetch_add(1); }

extern "C" void emucorec_settingsEndBatch() {
  // Nested/unbalanced calls must not drop the save; only the outermost end flushes.
  if (g_settings_batch_depth.fetch_sub(1) != 1) {
    return;
  }
  std::lock_guard lock(g_global_settings_mutex);
  write_global_settings_edits();
}


static bool settings_set(std::string_view path, std::string_view valueString, bool persist, bool queue_game = false) {
  auto root = find_cfg_node(&g_cfg, path);

  if (root == nullptr) {
    emucorec_android.error("settingsSet: node %s not found", path);
    return false;
  }

  // Values arrive JSON-encoded: bools/numbers bare, enums and strings quoted.
  // cfg::from_string wants the raw value, so unwrap one level of quoting and
  // undo the escapes we emit above.
  std::string decoded;
  if (valueString.size() >= 2 && valueString.front() == '"' &&
      valueString.back() == '"') {
    decoded.reserve(valueString.size() - 2);
    for (std::size_t i = 1; i + 1 < valueString.size(); ++i) {
      if (valueString[i] == '\\' && i + 2 < valueString.size()) {
        switch (valueString[++i]) {
        case 'n': decoded += '\n'; break;
        case 'r': decoded += '\r'; break;
        case 't': decoded += '\t'; break;
        case '"': decoded += '"'; break;
        case '\\': decoded += '\\'; break;
        default: decoded += valueString[i]; break;
        }
      } else {
        decoded += valueString[i];
      }
    }
  } else {
    decoded.assign(valueString);
  }

#ifndef RSX_GLES
  if (root == &g_cfg.video.renderer && decoded == "OpenGL") {
    emucorec_android.error(
        "settingsSet: OpenGL is unavailable because this Android build has no GLES backend");
    return false;
  }
#endif

  if (queue_game) {
    auto test = std::make_unique<cfg_root>();
    test->from_default();
    if (!test->from_string(g_cfg.to_string())) return false;
    auto *test_node = find_cfg_node(test.get(), path);
    if (!test_node || !test_node->from_string(decoded)) return false;
    std::lock_guard lock(g_game_settings_mutex);
    g_pending_game_settings.emplace_back(path, decoded);
    return true;
  }

  const std::string previous = root->to_string();
  if (!root->from_string(decoded, !Emu.IsStopped())) {
    emucorec_android.error("settingsSet: node %s does not accept value '%s'", path,
                        decoded);
    return false;
  }

  if (persist) {
    std::lock_guard lock(g_global_settings_mutex);
    g_global_settings_edits.emplace_back(path, decoded);
    if (g_settings_batch_depth.load() <= 0 && !write_global_settings_edits()) {
      g_global_settings_edits.pop_back();
      root->from_string(previous);
      return false;
    }
  }

  // The overlays own live objects built from the config; they are not re-read
  // per frame. reset_*_overlay() is what applies a change to the running one
  // (and creates it if it is newly enabled).
  //
  // Android only ever called these from rsx::thread::on_task(), i.e. once when
  // the RSX thread starts -- so changing an overlay setting mid-session did
  // nothing until the next boot. Desktop re-applies after every settings change
  // in main_application::OnEmuSettingsChange(), which lives in
  // rpcs3qt/CMakeLists.txt and is therefore not part of this build.
  //
  // Scoped to the overlay nodes on purpose: applyTo() pushes ~200 settings at
  // boot and re-applying every overlay property 200 times would be pure waste.
  //
  // Not reset while stopped. Both of these walk the overlay display manager into
  // perf_metrics_overlay::update(), which touches RSX-owned state, and a settings apply
  // landing while a game is starting or closing aborted inside update(). That is the
  // reported "changing any setting in game crashes the app". Nothing worth resetting
  // exists when no game is running, so this guard is the whole fix.
  //
  // Do NOT route these through Emu.CallFromMainThread hoping to make them safer. It
  // defers nothing here: the Android call_from_main_thread callback runs the function
  // INLINE on the caller's thread. All it adds is RPCS3's state-guard wrapper, a
  // std::function allocation and a log call, on a path applyTo hits eleven times per
  // settings change. Doing that livelocked Minecraft on load: one SPU pinned in
  // do_putllc retrying a reservation forever, the PPU stalled behind it and the RSX
  // spinning idle. Bisected to this single line, and reverting it restored the game.
  if (Emu.IsStopped()) {
    return true;
  }

  if (path.starts_with("Video@@Performance Overlay")) {
    rsx::overlays::reset_performance_overlay();
  } else if (path.starts_with("Video@@Debug overlay") ||
             path.starts_with("Miscellaneous@@Debug overlay")) {
    rsx::overlays::reset_debug_overlay();
  }
  return true;
}

extern "C" bool emucorec_settingsSet(std::string_view path, std::string_view value) {
  return settings_set(path, value, true);
}

extern "C" bool emucorec_settingsSetTransient(std::string_view path, std::string_view value) {
  return settings_set(path, value, false);
}

extern "C" bool emucorec_queueGameSetting(std::string_view path, std::string_view value) {
  return settings_set(path, value, false, true);
}

extern "C" std::string emucorec_getVersion() {
  return rpcs3::get_version().to_string();
}

extern "C" void emucorec_setMicrophoneDevices(JNIEnv* env, jobjectArray names, jintArray ids) {
  if (!env || !names || !ids) return;
  const jsize count = std::min(env->GetArrayLength(names), env->GetArrayLength(ids));
  std::vector<jint> device_ids(count);
  if (count) env->GetIntArrayRegion(ids, 0, count, device_ids.data());
  if (env->ExceptionCheck()) return;
  std::vector<std::pair<std::string, s32>> devices;
  devices.reserve(count);
  for (jsize i = 0; i < count; ++i) {
    auto name = static_cast<jstring>(env->GetObjectArrayElement(names, i));
    if (!name) continue;
    const char* chars = env->GetStringUTFChars(name, nullptr);
    if (chars) {
      devices.emplace_back(chars, device_ids[i]);
      env->ReleaseStringUTFChars(name, chars);
    }
    env->DeleteLocalRef(name);
  }
  oboe_capture::set_devices(std::move(devices));
}

extern "C" void emucorec_prepareUsbDevices(JNIEnv* env, jintArray descriptors) {
  if (!env || !descriptors) return;
  const jsize count = env->GetArrayLength(descriptors);
  std::vector<jint> input(count);
  if (count) env->GetIntArrayRegion(descriptors, 0, count, input.data());
  if (env->ExceptionCheck()) return;
  usb_android_prepare_devices(std::vector<int>(input.begin(), input.end()));
}

// Identity strings for the performance overlay header (app version, build and
// device name). Called by the app once the core is loaded.
extern "C" void emucorec_setAppInfo(std::string_view version,
                                  std::string_view build,
                                  std::string_view device) {
  rsx::overlays::set_app_info(std::string(version), std::string(build),
                              std::string(device));
}

#pragma GCC diagnostic pop
