# Keep JNI bridge entry points and classes touched from native code.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The JNI glue in app/src/main/cpp exports symbols named after
# com.sbro.emucorec.core.Ps3Core, and the native core resolves the static
# call-ins on com.sbro.emucorec.core.Ps3CoreCallbacks by fully-qualified name.
# Neither class name nor its signatures may change under shrinking.
-keep class com.sbro.emucorec.core.Ps3Core { *; }
-keep class com.sbro.emucorec.core.Ps3CoreCallbacks { *; }

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

# WorkManager persists enqueued work in its own database and restores it by
# class name, so the worker constructors must survive shrinking; a missing
# worker would silently drop scheduled backups and feedback uploads.
-keep class com.sbro.emucorec.feedback.FeedbackUploadWorker { *; }
-keep class com.sbro.emucorec.data.drive.DriveScheduleWorker { *; }
-keep class com.sbro.emucorec.data.drive.DriveBackupWorker { *; }

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

# Hub/Store catalogs and theme packages are decoded with kotlinx.serialization. Keep the generated
# companions/serializers so R8 cannot remove singletons reached through generated lookup paths.
-keepclassmembers class ** {
    *** Companion;
}
-keepclassmembers class **$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class **$$serializer {
    public static ** INSTANCE;
}
