# Keep JNI bridge entry points and classes touched from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}

# RPCSX exports JNI symbols with the concrete net.rpcsx.RPCSX class name.
# Native code also resolves these repository classes and callback methods by
# fully-qualified name, so neither class names nor constructors may be changed.
-keep class net.rpcsx.RPCSX { *; }
-keep class net.rpcsx.ProgressRepository { *; }
-keep class net.rpcsx.FirmwareRepository { *; }
-keep class net.rpcsx.GameRepository { *; }
-keep class net.rpcsx.GameInfo { *; }

-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}

# Ps3 bridge, provider, overlay and SDL wrappers are all used either by
# manifest reflection, native callbacks, or SDL's own runtime lookup.
# Ps3InstallBridge.onNativeProgress is invoked from JNI via GetStaticMethodID,
# so the whole class must survive shrinking.
-keep class com.sbro.emucorec.core.Ps3InstallBridge { *; }
-keep class com.sbro.emucorec.core.ps3.Emulator { *; }
-keep class com.sbro.emucorec.core.ps3.EmuSurface { *; }
-keep class com.sbro.emucorec.core.ps3.provider.Ps3DocumentsProvider { *; }
-keep class com.sbro.emucorec.core.ps3.overlay.** { *; }
# Bundled SDL/HID classes live in org.libsdl.app to match SDL's own JNI
# expectations. SDL_android.c looks them up by FQN via FindClass at JNI_OnLoad
# and SDL3 callbacks resolve their static methods by name through reflection.
-keep class org.libsdl.app.** { *; }

# Preserve app components referenced by manifest/shortcuts/providers.
-keep class com.sbro.emucorec.MainActivity { *; }
-keep class com.sbro.emucorec.EmuCoreCApp { *; }
-keep class androidx.core.content.FileProvider { *; }

# The Discord bridge resolves JNI symbols by class name, and the SDK is used
# reflectively from the isolated :discord process.
-keep,includedescriptorclasses class com.sbro.emucorec.discord.DiscordNative { *; }
-keep class com.discord.socialsdk.** { *; }

# SDL.java loads ReLinker through reflection, so those names must stay stable
# once release shrinking/obfuscation is enabled.
-keep class com.getkeepsafe.relinker.** { *; }

# Emulator restarts itself through ProcessPhoenix.
-keep class com.jakewharton.processphoenix.** { *; }

# Keep Kotlin metadata and annotations that Compose / reflection-adjacent code
# may rely on when stack traces or external libraries inspect them.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Kotlinx Serialization
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$$serializer {
    public static final **$$serializer INSTANCE;
}

# Android YouTube Player & WebView JavaScript Interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.pierfrancescosoffritti.androidyoutubeplayer.core.** { *; }

# Archive and Compression (Zip4j, Junrar, Apache Commons Compress)
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.tukaani.xz.MemoryLimitException
-dontwarn org.tukaani.xz.SingleXZInputStream
-dontwarn org.tukaani.xz.XZInputStream
-dontwarn net.lingala.zip4j.**
-dontwarn com.github.junrar.**

