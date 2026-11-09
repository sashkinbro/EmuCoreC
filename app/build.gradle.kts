@file:Suppress("UnstableApiUsage", "DEPRECATION")

import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services) apply false
}

// Firebase is enabled by dropping google-services.json into the app module.
// The build stays green without it so profile auth can be added in place.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// ---------------------------------------------------------------------------
// Build libemucorec-core.so from C++ sources automatically before every build.
//
// The .so lives in jniLibs/arm64-v8a/ as a prebuilt, but the core sources live
// IN THIS REPOSITORY (this repo is a fork of RPCS3/rpcs3; the emulator core is
// under core/, whose CMakeLists.txt is the CMake project root, and android/ at
// the repository root is added back in from there behind if(ANDROID)). This
// task uses the NDK's own CMake + Ninja to compile the library (target
// emucorec-core) and copies the result into jniLibs so Android Studio always
// picks up the latest binary. The recipe mirrors android/configure.sh.
// ---------------------------------------------------------------------------
val androidSdkRoot: String = run {
    val local = Properties()
    rootProject.layout.projectDirectory.file("local.properties").asFile
        .takeIf(File::exists)?.inputStream()?.use(local::load)
    local.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: error("Android SDK not found. Set sdk.dir in local.properties or \$ANDROID_HOME.")
}

// NDK 29 (clang 21): upstream RPCS3's stated compiler floor is clang-19, and
// clang 19.0.1 mis-analyses fmt::throw_exception (see android/configure.sh).
val ndkVersion  = "29.0.14206865"
val cmakeVersion = "3.30.5"

val ndkDir    = "$androidSdkRoot/ndk/$ndkVersion"
val cmakeDir  = "$androidSdkRoot/cmake/$cmakeVersion/bin"
val cmakeExe  = if (org.gradle.internal.os.OperatingSystem.current().isWindows)
                    "$cmakeDir/cmake.exe" else "$cmakeDir/cmake"
val ninjaExe  = if (org.gradle.internal.os.OperatingSystem.current().isWindows)
                    "$cmakeDir/ninja.exe" else "$cmakeDir/ninja"

val localBuildProperties = Properties().apply {
    rootProject.layout.projectDirectory.file("local.properties").asFile
        .takeIf(File::exists)
        ?.inputStream()
        ?.use(::load)
}

fun buildConfigString(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"") + "\""

val feedbackEndpoint = localBuildProperties.getProperty("emucorec.feedback.endpoint")
    ?: ""
val feedbackApiKey = localBuildProperties.getProperty("emucorec.feedback.apiKey")
    ?: ""

// Discord Social SDK is an optional local build input used by the isolated
// :discord helper process. Point emucorec.discord.sdkDir at the extracted SDK
// to enable Rich Presence; everything degrades to "SDK unavailable" without it.
val discordApplicationId = "1536775623287115786"
val discordSdkDirectory = localBuildProperties.getProperty("emucorec.discord.sdkDir")
    ?.let(::File)
    ?.takeIf { sdkDir ->
        sdkDir.resolve("include/discordpp.h").isFile &&
            sdkDir.resolve("arm64-v8a/libdiscord_partner_sdk.so").isFile &&
            sdkDir.resolve("discord_partner_sdk.aar").isFile
    }

// Repository root. The CMake project root itself is core/ (this repo is a fork
// of RPCS3/rpcs3); android/ at this root is added back in from core's
// CMakeLists behind if(ANDROID).
val repoRoot   = rootProject.layout.projectDirectory.asFile.absolutePath
val coreRoot   = "$repoRoot/core"
val buildDir2  = layout.buildDirectory.dir("emucorec-core-build").get().asFile

// ---------------------------------------------------------------------------
// Refresh the bundled RPCS3 per-title config database snapshot.
// Downloads https://api.rpcs3.net/config/?api=v1 into
// src/main/assets/rpcs3_config_db.json; ConfigDatabaseSeeder splits it into
// config_db/<TITLE_ID>.yml on device at runtime. Run manually when the
// snapshot gets stale. Regular builds use the committed file as-is (offline
// safe) and never touch the network.
// ---------------------------------------------------------------------------
val updateConfigDb by tasks.registering(Exec::class) {
    description = "Refresh bundled RPCS3 per-title config database snapshot"
    group = "emucorec"
    workingDir = projectDir
    commandLine(
        "curl", "-fsSL",
        "https://api.rpcs3.net/config/?api=v1",
        "-o", "src/main/assets/rpcs3_config_db.json",
    )
}

// ---------------------------------------------------------------------------
// Build FFmpeg 8.x for Android (aarch64) from source: the RPCS3 submodule only
// carries headers, and the old 5.1 prebuilts are too old for the FFmpeg 6+ APIs
// RPCS3 master uses. Output lands in app/build/ffmpeg-android, consumed by
// android/ffmpeg.cmake via 3rdparty_ffmpeg.
// ---------------------------------------------------------------------------
val ffmpegOutDir = File(projectDir, "build/ffmpeg-android")

val buildFfmpeg by tasks.registering(Exec::class) {
    description = "Build FFmpeg 8.1.1 for Android (aarch64)"
    group = "build"
    workingDir = rootProject.layout.projectDirectory.asFile
    commandLine("C:/Program Files/Git/bin/bash.exe", "android/build-ffmpeg.sh")
    onlyIf {
        if (File(ffmpegOutDir, "lib/libavcodec.a").exists()) {
            logger.lifecycle("FFmpeg already built at $ffmpegOutDir; skipping.")
            false
        } else {
            true
        }
    }
}

val configureEmuCorecCore by tasks.registering(Exec::class) {
    description = "Configure libemucorec-core.so CMake project"
    group = "build"
    dependsOn(buildFfmpeg)

    // Re-run configure if build.ninja is missing (e.g. first run, or after a failed/interrupted configure).
    // Skip entirely when the core sources are not present: the app then builds
    // with whatever libemucorec-core.so is already staged in jniLibs.
    onlyIf {
        val glueCmake = File(repoRoot, "android/CMakeLists.txt")
        val coreCmake = File(coreRoot, "CMakeLists.txt")
        if (!glueCmake.exists() || !coreCmake.exists()) {
            logger.warn("Core sources missing at $coreRoot; using staged jniLibs core instead.")
            return@onlyIf false
        }
        !File(buildDir2, "build.ninja").exists()
    }

    doFirst { buildDir2.mkdirs() }

    // The environment is fixed inside android/configure-core.cmd: Gradle's Exec
    // environment does not survive into cmake's nested FetchContent processes,
    // where `git submodule update` needs the Git for Windows bin dirs.
    workingDir = buildDir2
    val configureArgs = mutableListOf(
        "cmd", "/c",
        "$repoRoot/android/configure-core.cmd",
        cmakeExe,
        ninjaExe,
        ndkDir,
        buildDir2.absolutePath
    )
    discordSdkDirectory?.let { configureArgs += "-DDISCORD_SDK_DIR=${it.invariantSeparatorsPath}" }
    commandLine(configureArgs)
}

val buildEmuCorecCore by tasks.registering(Exec::class) {
    description = "Compile libemucorec-core.so from C++ sources using NDK CMake/Ninja"
    group = "build"
    dependsOn(configureEmuCorecCore)

    val mingwBin = "${System.getenv("LOCALAPPDATA")?.replace("\\", "/")}/mingw/mingw64/bin"
    environment("PATH", "$mingwBin;${System.getenv("PATH")}")

    workingDir = buildDir2
    val ninjaTargets = mutableListOf("-C", buildDir2.absolutePath, "emucorec-core")
    providers.environmentVariable("NINJA_JOBS").orNull?.let { requestedJobs ->
        val jobs = requestedJobs.toIntOrNull()
        require(jobs != null && jobs > 0) { "NINJA_JOBS must be a positive integer" }
        ninjaTargets.addAll(0, listOf("-j", jobs.toString()))
    }
    if (discordSdkDirectory != null) ninjaTargets += "emucorec_discord"
    commandLine(ninjaExe, *ninjaTargets.toTypedArray())

    doLast {
        val builtSo = File(buildDir2, "android/libemucorec-core.so")
        if (!builtSo.exists()) {
            throw GradleException("Build succeeded but libemucorec-core.so not found at ${builtSo.absolutePath}")
        }
        // Strip like the official builds, shrinking the unstripped artifact with
        // static LLVM to the release size.
        val stripExe = "$ndkDir/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip"
            .let { if (org.gradle.internal.os.OperatingSystem.current().isWindows) "$it.exe" else it }
        val stripped = File(buildDir2, "libemucorec-core-stripped.so")
        val strip = ProcessBuilder(stripExe, "-o", stripped.absolutePath, builtSo.absolutePath)
            .redirectErrorStream(true)
            .start()
        if (strip.waitFor() != 0) {
            throw GradleException("llvm-strip failed: ${strip.inputStream.bufferedReader().readText()}")
        }
        val jniLibsDir = File(projectDir, "src/main/jniLibs/arm64-v8a")
        jniLibsDir.mkdirs()
        stripped.copyTo(File(jniLibsDir, "libemucorec-core.so"), overwrite = true)
        stripped.delete()
        println("Successfully built and copied libemucorec-core.so to jniLibs/arm64-v8a")
        val builtDiscordSo = File(buildDir2, "android/libemucorec_discord.so")
        if (builtDiscordSo.exists()) {
            builtDiscordSo.copyTo(File(jniLibsDir, "libemucorec_discord.so"), overwrite = true)
            println("Copied libemucorec_discord.so to jniLibs/arm64-v8a")
        }
    }
}

android {
    namespace = "com.sbro.emucorec"
    ndkVersion = "29.0.14206865"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sbro.emucorec"
        minSdk = 29
        targetSdk = 37
        versionCode = 44
        versionName = "0.1.2"

        buildConfigField("String", "FEEDBACK_ENDPOINT", buildConfigString(feedbackEndpoint))
        buildConfigField("String", "FEEDBACK_API_KEY", buildConfigString(feedbackApiKey))
        buildConfigField("long", "DISCORD_APPLICATION_ID", "${discordApplicationId}L")
        buildConfigField("boolean", "DISCORD_SDK_AVAILABLE", (discordSdkDirectory != null).toString())
        manifestPlaceholders["discordSdkAvailable"] = (discordSdkDirectory != null).toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    buildTypes {
        debug {
            isJniDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("src/main/java"))
            res.setSrcDirs(listOf("src/main/res"))
            // RPCS3 native overlays resolve resources from the runtime tree,
            // so package the upstream Icons directory for first-run extraction
            // (bin/ lives inside the core/ tree now).
            assets.setSrcDirs(listOf("src/main/assets", "../core/bin"))
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
            discordSdkDirectory?.let(jniLibs::srcDir)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libdiscord_partner_sdk.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.core.google.shortcuts)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.process.phoenix)
    implementation(libs.relinker)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.android.youtube.player)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.google.play.review)
    implementation(libs.google.play.review.ktx)
    implementation(libs.zip4j)
    implementation(libs.junrar)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.google.identity)
    implementation(libs.google.auth)
    discordSdkDirectory?.let { sdkDir ->
        implementation(files(sdkDir.resolve("discord_partner_sdk.aar")))
    }

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

afterEvaluate {
    tasks.matching { it.name.startsWith("preBuild") }.configureEach {
        dependsOn(buildEmuCorecCore)
    }
}
