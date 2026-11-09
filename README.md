# EmuCoreC

[![License: GPL-2.0](https://img.shields.io/badge/License-GPL--2.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84.svg?logo=android)](#requirements)
[![Discord](https://img.shields.io/badge/Discord-Community-5865F2.svg?logo=discord&logoColor=white)](https://discord.gg/c5EBeNRpz2)
[![Website](https://img.shields.io/badge/Website-emucorec.web.app-4A90D9.svg)](https://emucorec.web.app)

EmuCoreC is a PlayStation 3 emulator application for Android. It combines a focused mobile interface with a core built on the latest RPCS3 emulator: a clean game library and catalog, one-tap installers, firmware download, trophies, save-data tools, per-game profiles, and the full RPCS3 settings tree — all local, no accounts required.

> [!NOTE]
> This repository is a fork of [RPCS3/rpcs3](https://github.com/RPCS3/rpcs3). The emulator core lives under `core/` — the upstream fork content (`rpcs3/`, `Utilities/`, `3rdparty/`, `bin/`, `buildfiles/`, ...) with `core/CMakeLists.txt` as the CMake project root. `android/` at the repository root contains the EmuCoreC Android adaptation (JNI glue, input, build glue); everything under `app/` is the EmuCoreC Android application.

> [!WARNING]
> EmuCoreC is experimental. Compatibility and performance vary by game, SoC, GPU driver, and Android version. The current build targets `arm64-v8a` only.

## Screenshots

<p align="center">
  <img src="screenshots/Screenshot_20260816-1419225.png" width="47%" />
  <img src="screenshots/Screenshot_20260817-180702.png" width="47%" />
</p>
<p align="center">
  <img src="screenshots/Screenshot_20260817-180717.png" width="47%" />
  <img src="screenshots/Screenshot_20260817-203023.png" width="47%" />
</p>
<p align="center">
  <img src="screenshots/Screenshot_20260817-205008.png" width="47%" />
  <img src="screenshots/Screenshot_20260817-205937.png" width="47%" />
</p>
<p align="center">
  <img src="screenshots/Screenshot_20260817-205957.png" width="47%" />
  <img src="screenshots/Screenshot_20260817-210743.png" width="47%" />
</p>
<p align="center">
  <img src="screenshots/Screenshot_20260817-211242.png" width="47%" />
  <img src="screenshots/Screenshot_20260817-212932.png" width="47%" />
</p>

## Features

- PS3 game discovery using `PARAM.SFO` and exact `TITLE_ID` serials
- Local PS3 catalog with IGDB metadata, cover art, screenshots, and videos
- Official RPCS3 compatibility data matched by PS3 title ID
- Direct download and installation of the official Sony `PS3UPDAT.PUP` firmware
- PKG, split-PKG, ISO, RAP, and EDAT installation through the core
- Safe ZIP/RAR extraction with multi-volume support, path validation, and free-space guards
- Installed-game library, details, play time, trophies, save-data tools, shortcuts, and per-game profiles
- Full live settings browser generated from the RPCS3 configuration tree
- Global and per-game core overrides with isolation between titles
- Touch controls, physical gamepad mapping, gyroscope mapping, vibration, and custom Adreno GPU drivers
- Localized interface resources for 12 languages

EmuCoreC does not include PlayStation 3 firmware, games, licenses, keys, or copyrighted game assets. Use only content you are legally entitled to use.

## Requirements

- Android 10 (API 29) or newer
- ARM64 device (`arm64-v8a`)

## Building

### Prerequisites

- Android Studio with the Android SDK
- JDK 17 or newer (Android Studio's bundled JBR works)
- Android NDK `29.0.14206865`
- CMake 3.30 or newer and Ninja (bundled with Android Studio's SDK manager)
- Git with submodule support
- On Windows, building the core from source also needs MinGW-w64 (`gcc`/`g++`, e.g. under `%LOCALAPPDATA%\mingw\mingw64\bin`) and Python 3 — both are used by LLVM's native cross-compile step

Clone the repository and initialize the submodules (the RPCS3 `3rdparty` dependencies; the core itself is part of this fork):

```powershell
git clone --recursive https://github.com/sashkinbro/EmuCoreC.git
cd EmuCoreC
git submodule update --init --recursive
```

### Core builds

The core is built from the `core/` CMake project (the `android/` glue at the repository root is added back in from `core/CMakeLists.txt` behind `if(ANDROID)`, target `emucorec-core`) automatically before every app build. The `:app:buildEmuCorecCore` Gradle task configures with the NDK toolchain via `android/configure-core.cmd` (mirroring `android/configure.sh`), compiles the core, strips the result, and stages it at:

```text
app/src/main/jniLibs/arm64-v8a/libemucorec-core.so
```

If the core sources are not present, Gradle skips the local build and uses the staged library as-is.

The emulator tree is relocated under `core/`, and the Android fork has its own
native changes. Fetch both sources, then reconcile their changes against the
recorded integration snapshot:

```powershell
git fetch --no-recurse-submodules upstream
git fetch --no-recurse-submodules armsx3
```

Integrated source snapshots: RPCS3 `47599599a4` and ARMSX3 `284b8c06b2`.

### App builds

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

The APK is written under `app/build/outputs/apk/`.

## Firmware and game data

The onboarding downloader retrieves `PS3UPDAT.PUP` directly from Sony's official update server. EmuCoreC does not host, mirror, modify, or redistribute the firmware.

Installed PS3 games are indexed by the title ID read from `PARAM.SFO` (for example `BLUS30443`). Catalog covers and metadata are resolved by exact serial mapping in `games.db`; name matching is not used for the installed-game "Open in catalog" action.

The content picker accepts direct PS3 files and complete ZIP/RAR volume sets. Archives are extracted to isolated temporary storage, checked for traversal paths and links, limited by available space, scanned for supported content, installed in natural part order, and removed after the operation. Password-protected archives must currently be extracted by the user first.

## Project structure

- `app/src/main/java/com/sbro/emucorec` — EmuCoreC Android application and UI
- `app/src/main/java/com/sbro/emucorec/core/Ps3Core.kt` — the bridge API into the core (JNI contract: `Ps3Core` methods and the `Ps3CoreCallbacks` call-ins)
- `app/src/main/cpp` — JNI glue: loads `libemucorec-core.so` and forwards to its `emucorec_*` entry points
- `android/` — the EmuCoreC Android adaptation of the RPCS3 core (glue, input, build)
- `core/` — the RPCS3 emulator core (upstream fork content; `core/CMakeLists.txt` is the CMake project root)

## Notes

- Releases marked as "parallel" are identical to the primary build but use an alternate package ID to allow side-by-side installation.

## Credits

EmuCoreC is grateful to the upstream projects:

- [RPCS3](https://github.com/RPCS3/rpcs3) — the original open-source PlayStation 3 emulator on which the core is based. Thank you to every RPCS3 contributor for the years of emulator research and development that make this application possible.
- [ARMSX3](https://github.com/ARMSX2/ARMSX3) — Android and ARM64 runtime, graphics, performance and game compatibility improvements manually adapted into EmuCoreC.
- [libadrenotools](https://github.com/bylaws/libadrenotools) — custom Adreno GPU driver loading support.
- [Zip4j](https://github.com/srikanth-lingala/zip4j) — ZIP and split-ZIP decoding.
- [Junrar](https://github.com/junrar/junrar) — RAR and multi-volume RAR decoding under its own UnRAR license.
- Game metadata and artwork references are generated from [IGDB](https://www.igdb.com/). RPCS3 compatibility status comes from the official [RPCS3 compatibility database](https://rpcs3.net/compatibility).

EmuCoreC is an independent project and is not affiliated with Sony Interactive Entertainment, RPCS3, or IGDB.

## Links

- Website: https://emucorec.web.app
- Repository: https://github.com/sashkinbro/EmuCoreC
- Discord: https://discord.gg/c5EBeNRpz2
- Support: https://www.patreon.com/c/emucore/membership

## License

The repository is distributed under the GNU General Public License version 2. Individual third-party components retain their own licenses; consult their source headers, license files, and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

See [LICENSE](LICENSE).
