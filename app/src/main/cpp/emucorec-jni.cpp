#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

// The JNI surface for com.sbro.emucorec.core.Ps3Core.
//
// This library is the ONLY thing Java/Kotlin loads directly; it then dlopen()s
// libemucorec-core.so (the emulator) and forwards every call to the core's
// emucorec_* entry points. Keeping the two separate means a failed or missing
// core surfaces as a clean failure from openLibrary() instead of a load-time
// crash of the app process.
//
// If a method here is renamed, the matching method in Ps3Core.kt must change in
// lockstep: the symbol names are derived from the Kotlin class path.

namespace {

constexpr const char *kLogTag = "EmuCoreC-JNI";

struct EmuCoreCApi {
  bool (*overlayPadData)(int port, int digital1, int digital2, int leftStickX,
                         int leftStickY, int rightStickX, int rightStickY);
  void (*setSocInfo)(std::string_view socInfo);
  void (*setLogLevelFloor)(int level);
  bool (*initialize)(std::string_view rootDir, std::string_view user);
  bool (*processCompilationQueue)(JNIEnv *env);
  bool (*startMainThreadProcessor)(JNIEnv *env);
  int (*boot)(std::string_view path_);
  int (*getState)();
  void (*kill)();
  void (*pause)();
  void (*resume)();
  void (*openHomeMenu)();
  std::string (*getTitleId)();
  std::string (*getUser)();
  bool (*surfaceEvent)(JNIEnv *env, jobject surface, jint event);
  void (*surfaceSizeChanged)(int width, int height);
  void (*displayRefreshRateChanged)(double refreshRate);
  bool (*installFw)(JNIEnv *env, int fd, long progressId);
  bool (*install)(JNIEnv *env, int fd, long progressId);
  bool (*installSplitPkg)(JNIEnv *env, const int *fds, int count,
                          long progressId);
  bool (*installKey)(JNIEnv *env, int fd, long progressId,
                     std::string_view gamePath);
  std::string (*settingsGet)(std::string_view path);
  bool (*settingsSet)(std::string_view path, std::string_view valueString);
  bool (*settingsSetTransient)(std::string_view path, std::string_view valueString);
  bool (*queueGameSetting)(std::string_view path, std::string_view valueString);
  void (*clearPendingGameSettings)();
  void (*setMicrophoneDevices)(JNIEnv *, jobjectArray, jintArray);
  std::string (*getVersion)();
  std::string (*probeDiscInfo)(std::string_view path, std::string_view iconOut);
  bool (*requestSaveState)(unsigned slot, long progressId);
  bool (*saveStateBusy)();
  bool (*requestLoadState)(unsigned slot, long progressId);
  std::string (*getSaveStateSlots)();
  void (*setAppInfo)(std::string_view version, std::string_view build,
                     std::string_view device);
  std::string (*patchEngineVersion)();
  int (*patchesImport)(std::string_view content);
  std::string (*patchesList)(std::string_view serial);
  bool (*patchSetEnabled)(std::string_view hash, std::string_view description,
                          std::string_view serial, std::string_view appVersion,
                          bool enabled);
};

struct EmuCoreCLibrary : EmuCoreCApi {
  void *handle = nullptr;

  EmuCoreCLibrary() = default;
  EmuCoreCLibrary(const EmuCoreCLibrary &) = delete;
  EmuCoreCLibrary(EmuCoreCLibrary &&other) { swap(other); }
  EmuCoreCLibrary &operator=(EmuCoreCLibrary &&other) {
    swap(other);
    return *this;
  }
  ~EmuCoreCLibrary() {
    if (handle) {
      ::dlclose(handle);
    }
  }

  void swap(EmuCoreCLibrary &other) noexcept {
    std::swap(handle, other.handle);
    std::swap(static_cast<EmuCoreCApi &>(*this), static_cast<EmuCoreCApi &>(other));
  }

  // Entry points without which no core functionality exists. Everything else
  // degrades gracefully (save states report unsupported, patches fall back to
  // defaults), so a core missing only those still opens.
  bool hasRequiredSymbols() const {
    return initialize && processCompilationQueue && startMainThreadProcessor &&
           boot && getState && kill && pause && resume && getTitleId &&
           getUser && surfaceEvent && surfaceSizeChanged &&
           displayRefreshRateChanged && overlayPadData && installFw &&
           install && installSplitPkg && installKey && settingsGet &&
           settingsSet && getVersion;
  }

  static std::optional<EmuCoreCLibrary> Open(const char *path) {
    void *handle = ::dlopen(path, RTLD_LOCAL | RTLD_NOW);
    if (handle == nullptr) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "Failed to open the core at %s: %s", path,
                          ::dlerror());
      return {};
    }

    EmuCoreCLibrary result;
    result.handle = handle;

    // clang-format off
    result.overlayPadData = reinterpret_cast<decltype(overlayPadData)>(dlsym(handle, "emucorec_overlayPadData"));
    result.setSocInfo = reinterpret_cast<decltype(setSocInfo)>(dlsym(handle, "emucorec_setSocInfo"));
    result.setLogLevelFloor = reinterpret_cast<decltype(setLogLevelFloor)>(dlsym(handle, "emucorec_setLogLevelFloor"));
    result.initialize = reinterpret_cast<decltype(initialize)>(dlsym(handle, "emucorec_initialize"));
    result.processCompilationQueue = reinterpret_cast<decltype(processCompilationQueue)>(dlsym(handle, "emucorec_processCompilationQueue"));
    result.startMainThreadProcessor = reinterpret_cast<decltype(startMainThreadProcessor)>(dlsym(handle, "emucorec_startMainThreadProcessor"));
    result.boot = reinterpret_cast<decltype(boot)>(dlsym(handle, "emucorec_boot"));
    result.getState = reinterpret_cast<decltype(getState)>(dlsym(handle, "emucorec_getState"));
    result.kill = reinterpret_cast<decltype(kill)>(dlsym(handle, "emucorec_kill"));
    result.pause = reinterpret_cast<decltype(pause)>(dlsym(handle, "emucorec_pause"));
    result.resume = reinterpret_cast<decltype(resume)>(dlsym(handle, "emucorec_resume"));
    result.openHomeMenu = reinterpret_cast<decltype(openHomeMenu)>(dlsym(handle, "emucorec_openHomeMenu"));
    result.getTitleId = reinterpret_cast<decltype(getTitleId)>(dlsym(handle, "emucorec_getTitleId"));
    result.getUser = reinterpret_cast<decltype(getUser)>(dlsym(handle, "emucorec_getUser"));
    result.surfaceEvent = reinterpret_cast<decltype(surfaceEvent)>(dlsym(handle, "emucorec_surfaceEvent"));
    result.surfaceSizeChanged = reinterpret_cast<decltype(surfaceSizeChanged)>(dlsym(handle, "emucorec_surfaceSizeChanged"));
    result.displayRefreshRateChanged = reinterpret_cast<decltype(displayRefreshRateChanged)>(dlsym(handle, "emucorec_displayRefreshRateChanged"));
    result.installFw = reinterpret_cast<decltype(installFw)>(dlsym(handle, "emucorec_installFw"));
    result.install = reinterpret_cast<decltype(install)>(dlsym(handle, "emucorec_install"));
    result.installSplitPkg = reinterpret_cast<decltype(installSplitPkg)>(dlsym(handle, "emucorec_installSplitPkg"));
    result.installKey = reinterpret_cast<decltype(installKey)>(dlsym(handle, "emucorec_installKey"));
    result.settingsGet = reinterpret_cast<decltype(settingsGet)>(dlsym(handle, "emucorec_settingsGet"));
    result.settingsSet = reinterpret_cast<decltype(settingsSet)>(dlsym(handle, "emucorec_settingsSet"));
    result.settingsSetTransient = reinterpret_cast<decltype(settingsSetTransient)>(dlsym(handle, "emucorec_settingsSetTransient"));
    result.queueGameSetting = reinterpret_cast<decltype(queueGameSetting)>(dlsym(handle, "emucorec_queueGameSetting"));
    result.clearPendingGameSettings = reinterpret_cast<decltype(clearPendingGameSettings)>(dlsym(handle, "emucorec_clearPendingGameSettings"));
    result.setMicrophoneDevices = reinterpret_cast<decltype(setMicrophoneDevices)>(dlsym(handle, "emucorec_setMicrophoneDevices"));
    result.getVersion = reinterpret_cast<decltype(getVersion)>(dlsym(handle, "emucorec_getVersion"));
    result.probeDiscInfo = reinterpret_cast<decltype(probeDiscInfo)>(dlsym(handle, "emucorec_probeDiscInfo"));
    result.requestSaveState = reinterpret_cast<decltype(requestSaveState)>(dlsym(handle, "emucorec_requestSaveState"));
    result.saveStateBusy = reinterpret_cast<decltype(saveStateBusy)>(dlsym(handle, "emucorec_saveStateBusy"));
    result.requestLoadState = reinterpret_cast<decltype(requestLoadState)>(dlsym(handle, "emucorec_requestLoadState"));
    result.getSaveStateSlots = reinterpret_cast<decltype(getSaveStateSlots)>(dlsym(handle, "emucorec_getSaveStateSlots"));
    result.setAppInfo = reinterpret_cast<decltype(setAppInfo)>(dlsym(handle, "emucorec_setAppInfo"));
    result.patchEngineVersion = reinterpret_cast<decltype(patchEngineVersion)>(dlsym(handle, "emucorec_patchEngineVersion"));
    result.patchesImport = reinterpret_cast<decltype(patchesImport)>(dlsym(handle, "emucorec_patchesImport"));
    result.patchesList = reinterpret_cast<decltype(patchesList)>(dlsym(handle, "emucorec_patchesList"));
    result.patchSetEnabled = reinterpret_cast<decltype(patchSetEnabled)>(dlsym(handle, "emucorec_patchSetEnabled"));
    // clang-format on

    if (!result.hasRequiredSymbols()) {
      // Refuse to hand back a core whose ABI does not match this glue. Without
      // this check the app would open the library and then crash on the first
      // null function pointer, with nothing in the log to say why.
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "Core at %s is missing required emucorec_* entry points",
                          path);
      return {};
    }

    return result;
  }
};

EmuCoreCLibrary emucorecLib;

std::string unwrap(JNIEnv *env, jstring string) {
  auto resultBuffer = env->GetStringUTFChars(string, nullptr);
  std::string result(resultBuffer);
  env->ReleaseStringUTFChars(string, resultBuffer);
  return result;
}

jstring wrap(JNIEnv *env, const std::string &string) {
  return env->NewStringUTF(string.c_str());
}

jstring wrap(JNIEnv *env, const char *string) {
  return env->NewStringUTF(string);
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sbro_emucorec_core_Ps3Core_openLibrary(JNIEnv *env, jobject, jstring path) {
  if (auto library = EmuCoreCLibrary::Open(unwrap(env, path).c_str())) {
    emucorecLib = std::move(*library);
    return true;
  }

  return false;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_overlayPadData(
    JNIEnv *, jobject, jint digital1, jint digital2, jint leftStickX,
    jint leftStickY, jint rightStickX, jint rightStickY) {
  // The core export takes a port as its first argument; the app drives player 1
  // only. Passing digital1 in that slot shifted every value by one: dpad
  // presses landed in the port check and were dropped, face buttons were
  // interpreted as dpad, and the sticks read each other's values.
  return emucorecLib.overlayPadData(0, digital1, digital2, leftStickX,
                                    leftStickY, rightStickX, rightStickY);
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_setSocInfo(
    JNIEnv *env, jobject, jstring jsocInfo) {
  if (emucorecLib.setSocInfo == nullptr) {
    return;
  }
  emucorecLib.setSocInfo(unwrap(env, jsocInfo));
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_setLogLevelFloor(
    JNIEnv *, jobject, jint level) {
  if (emucorecLib.setLogLevelFloor == nullptr) {
    return;
  }
  emucorecLib.setLogLevelFloor(level);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_initialize(
    JNIEnv *env, jobject, jstring rootDir, jstring user) {
  return emucorecLib.initialize(unwrap(env, rootDir), unwrap(env, user));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sbro_emucorec_core_Ps3Core_processCompilationQueue(JNIEnv *env, jobject) {
  return emucorecLib.processCompilationQueue(env);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sbro_emucorec_core_Ps3Core_startMainThreadProcessor(JNIEnv *env, jobject) {
  return emucorecLib.startMainThreadProcessor(env);
}

extern "C" JNIEXPORT jint JNICALL Java_com_sbro_emucorec_core_Ps3Core_boot(
    JNIEnv *env, jobject, jstring jpath) {
  return emucorecLib.boot(unwrap(env, jpath));
}

extern "C" JNIEXPORT jint JNICALL Java_com_sbro_emucorec_core_Ps3Core_getState(
    JNIEnv *, jobject) {
  return emucorecLib.getState();
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_kill(
    JNIEnv *, jobject) {
  emucorecLib.kill();
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_pause(
    JNIEnv *, jobject) {
  emucorecLib.pause();
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_resume(
    JNIEnv *, jobject) {
  emucorecLib.resume();
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_openHomeMenu(
    JNIEnv *, jobject) {
  if (emucorecLib.openHomeMenu) {
    emucorecLib.openHomeMenu();
  }
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sbro_emucorec_core_Ps3Core_getTitleId(
    JNIEnv *env, jobject) {
  return wrap(env, emucorecLib.getTitleId());
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sbro_emucorec_core_Ps3Core_getUser(
    JNIEnv *env, jobject) {
  return wrap(env, emucorecLib.getUser());
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_surfaceEvent(
    JNIEnv *env, jobject, jobject surface, jint event) {
  return emucorecLib.surfaceEvent(env, surface, event);
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_surfaceSizeChanged(
    JNIEnv *, jobject, jint width, jint height) {
  emucorecLib.surfaceSizeChanged(width, height);
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_displayRefreshRateChanged(
    JNIEnv *, jobject, jdouble refreshRate) {
  emucorecLib.displayRefreshRateChanged(refreshRate);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_installFw(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  return emucorecLib.installFw(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_install(
    JNIEnv *env, jobject, jint fd, jlong progressId) {
  return emucorecLib.install(env, fd, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_sbro_emucorec_core_Ps3Core_installSplitPkg(JNIEnv *env, jobject, jintArray jfds,
                                                    jlong progressId) {
  if (jfds == nullptr) {
    return false;
  }

  const jsize count = env->GetArrayLength(jfds);
  if (count <= 0) {
    return false;
  }

  std::vector<jint> fds(static_cast<std::size_t>(count));
  env->GetIntArrayRegion(jfds, 0, count, fds.data());
  if (env->ExceptionCheck()) {
    return false;
  }

  static_assert(sizeof(jint) == sizeof(int));
  return emucorecLib.installSplitPkg(
      env, reinterpret_cast<const int *>(fds.data()), count, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_installKey(
    JNIEnv *env, jobject, jint fd, jlong progressId, jstring gamePath) {
  return emucorecLib.installKey(env, fd, progressId, unwrap(env, gamePath));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorec_core_Ps3Core_settingsGet(JNIEnv *env, jobject, jstring jpath) {
  return wrap(env, emucorecLib.settingsGet(unwrap(env, jpath)));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_settingsSet(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  return emucorecLib.settingsSet(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_settingsSetTransient(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  return emucorecLib.settingsSetTransient &&
         emucorecLib.settingsSetTransient(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_clearPendingGameSettings(
    JNIEnv *, jobject) {
  if (emucorecLib.clearPendingGameSettings) emucorecLib.clearPendingGameSettings();
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_setMicrophoneDevices(
    JNIEnv *env, jobject, jobjectArray names, jintArray ids) {
  if (emucorecLib.setMicrophoneDevices) emucorecLib.setMicrophoneDevices(env, names, ids);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_queueGameSetting(
    JNIEnv *env, jobject, jstring jpath, jstring jvalue) {
  return emucorecLib.queueGameSetting &&
         emucorecLib.queueGameSetting(unwrap(env, jpath), unwrap(env, jvalue));
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sbro_emucorec_core_Ps3Core_getVersion(
    JNIEnv *env, jobject) {
  return wrap(env, emucorecLib.getVersion());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorec_core_Ps3Core_probeDiscInfo(JNIEnv *env, jobject, jstring path, jstring iconOut) {
  return wrap(env, emucorecLib.probeDiscInfo
                      ? emucorecLib.probeDiscInfo(unwrap(env, path), unwrap(env, iconOut))
                      : "{}");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_saveStatesSupported(
    JNIEnv *, jobject) {
  return emucorecLib.requestSaveState && emucorecLib.requestLoadState &&
         emucorecLib.getSaveStateSlots && emucorecLib.saveStateBusy;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_saveStateBusy(
    JNIEnv *, jobject) {
  return emucorecLib.saveStateBusy && emucorecLib.saveStateBusy();
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_requestSaveState(
    JNIEnv *, jobject, jint slot, jlong progressId) {
  return slot >= 0 && slot < 10 && emucorecLib.requestSaveState &&
         emucorecLib.requestSaveState(slot, progressId);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_requestLoadState(
    JNIEnv *, jobject, jint slot, jlong progressId) {
  return slot >= 0 && slot < 10 && emucorecLib.requestLoadState &&
         emucorecLib.requestLoadState(slot, progressId);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sbro_emucorec_core_Ps3Core_saveStateSlots(
    JNIEnv *env, jobject) {
  const auto result =
      emucorecLib.getSaveStateSlots ? emucorecLib.getSaveStateSlots() : "[]";
  return wrap(env, result);
}

extern "C" JNIEXPORT void JNICALL Java_com_sbro_emucorec_core_Ps3Core_setAppInfo(
    JNIEnv *env, jobject, jstring jversion, jstring jbuild, jstring jdevice) {
  if (emucorecLib.setAppInfo == nullptr) {
    return;
  }
  emucorecLib.setAppInfo(unwrap(env, jversion), unwrap(env, jbuild),
                         unwrap(env, jdevice));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sbro_emucorec_core_Ps3Core_patchEngineVersion(JNIEnv *env, jobject) {
  if (emucorecLib.patchEngineVersion) {
    return wrap(env, emucorecLib.patchEngineVersion());
  }
  return wrap(env, "1.2");
}

extern "C" JNIEXPORT jint JNICALL Java_com_sbro_emucorec_core_Ps3Core_patchesImport(
    JNIEnv *env, jobject, jstring jcontent) {
  if (emucorecLib.patchesImport) {
    return emucorecLib.patchesImport(unwrap(env, jcontent));
  }
  return -1;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_sbro_emucorec_core_Ps3Core_patchesList(
    JNIEnv *env, jobject, jstring jserial) {
  if (emucorecLib.patchesList) {
    return wrap(env, emucorecLib.patchesList(unwrap(env, jserial)));
  }
  return wrap(env, "[]");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_sbro_emucorec_core_Ps3Core_patchSetEnabled(
    JNIEnv *env, jobject, jstring jhash, jstring jdescription, jstring jserial,
    jstring jappVersion, jboolean jenabled) {
  if (emucorecLib.patchSetEnabled) {
    return emucorecLib.patchSetEnabled(unwrap(env, jhash), unwrap(env, jdescription),
                                       unwrap(env, jserial), unwrap(env, jappVersion),
                                       jenabled);
  }
  return false;
}
