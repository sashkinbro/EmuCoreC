# Third-party notices

EmuCoreC combines original Android application code with separately licensed third-party components. This file is informational; the authoritative license text is the one distributed by each upstream project.

- **RPCS3** — the original open-source PlayStation 3 emulator. This repository is a fork of <https://github.com/RPCS3/rpcs3>; the core sources live under `core/`, with the EmuCoreC Android adaptation in `android/`. See the upstream license at <https://github.com/RPCS3/rpcs3>.
- **ARMSX3** — Android and ARM64 runtime, compatibility, graphics and performance changes manually adapted from <https://github.com/ARMSX2/ARMSX3>. Original contributor notices remain in the source files.
- **SPIRV-Cross** — shader translation support. See the license in `core/3rdparty/spirv-cross/SPIRV-Cross/LICENSE` and the retained source notices.
- **librashader C headers** — the MIT-licensed dynamic loader headers from <https://github.com/SnowflakePowered/librashader>, pinned as a submodule. The shader runtime is loaded dynamically when separately available; it is not statically linked into the emulator.
- **Eden frame generation implementation** — derived through ARMSX3/ARMSX2 from Camille LaVey's Eden implementation. The imported files retain their GPL-2.0-or-later notices and attribution under `core/rpcs3/Emu/RSX/VK/FrameGen/`.
- **libadrenotools** — native graphics-driver loading support. See the license in the `app/src/main/cpp/libadrenotools` submodule.
- **Zip4j** — ZIP and split-ZIP decoding, distributed under the Apache License 2.0. See <https://github.com/srikanth-lingala/zip4j>.
- **Junrar** — RAR and multi-volume RAR decoding. Junrar and the UnRAR source it incorporates are distributed under their upstream license terms, including the restriction against using the source to recreate the RAR compression algorithm. EmuCoreC uses the library only for decompression and does not modify it. See <https://github.com/junrar/junrar>.

Android, Kotlin, Jetpack Compose, SDL, and other transitive dependencies retain their respective upstream licenses and notices.
