package com.sbro.emucorec.ui.settings

import androidx.annotation.StringRes
import com.sbro.emucorec.R

/** One owner per native path; presentation groups never change the stored config path. */
internal fun coreSettingsCategory(path: String): Ps3CoreSettingsCategory = when {
    isDiagnosticCoreSetting(path) -> Ps3CoreSettingsCategory.Advanced
    path.startsWith("System@@HDD ") -> Ps3CoreSettingsCategory.Storage
    path in CONSOLE_INPUT_PATHS -> Ps3CoreSettingsCategory.Controls
    path.startsWith("System@@") -> Ps3CoreSettingsCategory.General
    path.startsWith("Miscellaneous@@Show ") -> Ps3CoreSettingsCategory.Overlay
    path.startsWith("Miscellaneous@@") -> Ps3CoreSettingsCategory.General
    path.startsWith("Video@@Performance Overlay@@") || path == "Video@@Performance Overlay" ||
        path.startsWith("Video@@Shader Loading Dialog@@") -> Ps3CoreSettingsCategory.Overlay
    path.startsWith("Video@@") -> Ps3CoreSettingsCategory.Graphics
    path.startsWith("Audio@@") -> Ps3CoreSettingsCategory.Audio
    path.startsWith("Input/Output@@") -> Ps3CoreSettingsCategory.Controls
    path.startsWith("VFS@@") || path.startsWith("Savestate@@") -> Ps3CoreSettingsCategory.Storage
    path.startsWith("Net@@") -> Ps3CoreSettingsCategory.Network
    else -> Ps3CoreSettingsCategory.Advanced
}

private val CONSOLE_INPUT_PATHS = setOf("System@@Keyboard Type", "System@@Enter button assignment")

private fun isDiagnosticCoreSetting(path: String): Boolean =
    path.startsWith("Log@@") || path.startsWith("Video@@Debug overlay") ||
        path in DIAGNOSTIC_PATHS

private val DIAGNOSTIC_PATHS = setOf(
    "Miscellaneous@@GDB Server", "Miscellaneous@@Silence All Logs",
    "System@@Process ARGV", "System@@Console PSID",
    "Audio@@Dump to file", "Video@@Log shader programs", "Video@@Debug Program Analyser",
    "Video@@RSX Profiler", "Video@@Debug output", "Video@@Disable Video Output",
    "Input/Output@@IO Debug overlay", "Input/Output@@Mouse Debug overlay",
    "Savestate@@Inspection Mode Savestates",
    "Core@@PPU Debug", "Core@@PPU Calling History", "Core@@Save LLVM logs",
    "Core@@SPU Debug", "Core@@MFC Debug", "Core@@SPU Profiler", "Core@@PPU Profiler",
    "Core@@Assume External Debugger",
)

/** Settings whose desktop UI/backend is replaced by Android app functionality. */
internal fun isAndroidCoreSettingSupported(path: String): Boolean = path !in DESKTOP_ONLY_PATHS

private val DESKTOP_ONLY_PATHS = setOf(
    "Miscellaneous@@Automatically start games after boot",
    "Miscellaneous@@Exit RPCS3 when process finishes",
    "Miscellaneous@@Pause emulation on RPCS3 focus loss",
    "Miscellaneous@@Start games in fullscreen mode",
    "Miscellaneous@@Start Big Picture Mode on boot",
    "Miscellaneous@@Prevent display sleep while running games",
    "Miscellaneous@@Use native user interface",
    "Miscellaneous@@Use recursive scan",
    "Miscellaneous@@Window Title Format",
    "Miscellaneous@@Enable GameMode",
    "Audio@@Music Handler",
    "Video@@Vulkan@@Adapter",
    "Video@@Vulkan@@Exclusive Fullscreen Mode",
    "Video@@Disable MSL Fast Math",
    "Input/Output@@Keyboard",
    "Input/Output@@Mouse",
    "Input/Output@@Camera ID",
    "Input/Output@@SDL Camera ID",
    "Input/Output@@Load SDL GameController Mappings",
    "Input/Output@@Background input enabled",
    "Input/Output@@Mouse-based gyro enabled",
)

/** Stable, localized groups in player-facing order, independent of JSON member order. */
internal enum class CoreSettingsGroup(@param:StringRes val titleRes: Int) {
    System(R.string.core_label_system),
    General(R.string.settings_tab_general),
    DisplayScaling(R.string.settings_group_display_scaling),
    Vulkan(R.string.core_label_vulkan),
    ShadersCache(R.string.settings_group_shaders_cache),
    RenderingCompatibility(R.string.settings_group_rendering_compatibility),
    PerformanceOverlay(R.string.core_label_performance_overlay),
    Notifications(R.string.settings_tab_overlay),
    ShaderLoading(R.string.core_label_shader_loading_dialog),
    AudioOutput(R.string.settings_group_audio_output),
    AudioBuffering(R.string.settings_group_audio_buffering),
    Microphone(R.string.core_label_microphone_devices),
    InputDevices(R.string.settings_group_input_devices),
    CpuCompilation(R.string.settings_group_cpu_compilation),
    CpuCompatibility(R.string.core_label_core),
    Diagnostics(R.string.settings_group_diagnostics),
    Storage(R.string.settings_tab_storage),
    SaveStates(R.string.emulation_menu_section_savestates),
    Network(R.string.settings_tab_network),
}

internal fun coreSettingsGroup(path: String): CoreSettingsGroup = when {
    isDiagnosticCoreSetting(path) -> CoreSettingsGroup.Diagnostics
    path.startsWith("System@@HDD ") -> CoreSettingsGroup.Storage
    path in CONSOLE_INPUT_PATHS -> CoreSettingsGroup.InputDevices
    path.startsWith("System@@") -> CoreSettingsGroup.System
    path.startsWith("Miscellaneous@@Show ") -> CoreSettingsGroup.Notifications
    path.startsWith("Miscellaneous@@") -> CoreSettingsGroup.General
    path.startsWith("Video@@Performance Overlay") -> CoreSettingsGroup.PerformanceOverlay
    path.startsWith("Video@@Shader Loading Dialog@@") -> CoreSettingsGroup.ShaderLoading
    path.startsWith("Video@@Vulkan@@") -> CoreSettingsGroup.Vulkan
    path == "Video@@Renderer" -> CoreSettingsGroup.DisplayScaling
    path.startsWith("Video@@") -> when {
        path in DISPLAY_PATHS || path.startsWith("Video@@Frame Generation") ||
            path.startsWith("Video@@VR ") || path.startsWith("Video@@Custom Anaglyph Matrices@@") -> CoreSettingsGroup.DisplayScaling
        path in SHADER_PATHS || path.startsWith("Video@@Shader Preset") -> CoreSettingsGroup.ShadersCache
        else -> CoreSettingsGroup.RenderingCompatibility
    }
    path.startsWith("Audio@@Microphone") -> CoreSettingsGroup.Microphone
    path in AUDIO_BUFFER_PATHS -> CoreSettingsGroup.AudioBuffering
    path.startsWith("Audio@@") -> CoreSettingsGroup.AudioOutput
    path.startsWith("Input/Output@@") -> CoreSettingsGroup.InputDevices
    path in CPU_COMPILATION_PATHS -> CoreSettingsGroup.CpuCompilation
    path.startsWith("Core@@") -> CoreSettingsGroup.CpuCompatibility
    path.startsWith("VFS@@") -> CoreSettingsGroup.Storage
    path.startsWith("Savestate@@") -> CoreSettingsGroup.SaveStates
    path.startsWith("Net@@") -> CoreSettingsGroup.Network
    else -> CoreSettingsGroup.Diagnostics
}

private val DISPLAY_PATHS = setOf(
    "Video@@Resolution", "Video@@Resolution Scale", "Video@@Minimum Scalable Dimension",
    "Video@@Aspect ratio", "Video@@Stretch To Display Area", "Video@@Display Aspect Override",
    "Video@@Frame limit", "Video@@VSync Mode", "Video@@Output Scaling Mode",
    "Video@@Anisotropic Filter Override", "Video@@MSAA", "Video@@Texture LOD Bias Addend",
    "Video@@FidelityFX CAS Sharpening Intensity", "Video@@SGSR Edge Sharpness",
    "Video@@3D Display Enabled", "Video@@3D Display Mode", "Video@@Screen size in inches", "Video@@Cardboard VR",
)
private val SHADER_PATHS = setOf(
    "Video@@Shader Mode", "Video@@Shader Precision", "Video@@Shader Compiler Threads",
    "Video@@Disable On-Disk Shader Cache",
)
private val AUDIO_BUFFER_PATHS = setOf(
    "Audio@@Enable Buffering", "Audio@@Desired Audio Buffer Duration",
    "Audio@@Enable Time Stretching", "Audio@@Time Stretching Threshold", "Audio@@Disable Sampling Skip",
)
private val CPU_COMPILATION_PATHS = setOf(
    "Core@@PPU Decoder", "Core@@SPU Decoder", "Core@@PPU Threads", "Core@@Preferred SPU Threads",
    "Core@@Max LLVM Compile Threads", "Core@@LLVM Precompilation", "Core@@SPU Cache",
    "Core@@SPU Block Size", "Core@@Thread Scheduler Mode", "Core@@Pause Game During SPU Compilation",
)

/** Primary controls precede their fine tuning, regardless of native JSON ordering. */
internal fun coreSettingOrder(path: String): Int = PRIMARY_SETTING_ORDER[path] ?: 100

private val PRIMARY_SETTING_ORDER = listOf(
    "Video@@Renderer", "Video@@Resolution", "Video@@Resolution Scale", "Video@@Output Scaling Mode",
    "Video@@FidelityFX CAS Sharpening Intensity", "Video@@SGSR Edge Sharpness",
    "Video@@Aspect ratio", "Video@@Stretch To Display Area", "Video@@Frame limit", "Video@@VSync Mode",
    "Audio@@Master Volume", "Audio@@Renderer", "Audio@@Audio Device", "Audio@@Audio Provider",
    "Audio@@Audio Format", "Audio@@Audio Channel Layout",
    "Audio@@Enable Buffering", "Audio@@Desired Audio Buffer Duration",
    "Audio@@Enable Time Stretching", "Audio@@Time Stretching Threshold",
    "Audio@@Microphone Type", "Audio@@Microphone Devices",
    "Video@@Performance Overlay@@Enabled", "Video@@Performance Overlay@@Detail level",
    "Video@@Performance Overlay@@Position", "Video@@Performance Overlay@@Font size (px)",
    "Video@@Performance Overlay@@Opacity (%)",
    "Core@@PPU Decoder", "Core@@SPU Decoder", "Core@@PPU Threads", "Core@@Preferred SPU Threads",
    "Core@@Thread Scheduler Mode", "Core@@SPU Block Size", "Core@@Max LLVM Compile Threads",
    "Core@@LLVM Precompilation", "Core@@SPU Cache",
).withIndex().associate { (index, path) -> path to index }
