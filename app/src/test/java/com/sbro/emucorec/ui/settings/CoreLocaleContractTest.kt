package com.sbro.emucorec.ui.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.isDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLocaleContractTest {
    private val legacyBranding = Regex("""(?i)\b(?:PS\s*)?Vita(?:3K)?\b|\bEmuCoreV\b""")
    private val mojibakeMarkers = listOf("Р’", "Р\\'", "В·", "Вµ", "RГ", "Рњ", "Рµ", "С–")
    private val removedDeadKeys = setOf("settings_core_disable_motion", "settings_help_disable_motion")

    @Test
    fun everySupportedLocaleContainsTheCompletePs3CoreContract() {
        val resourceRoot = locateResourceRoot()
        val required = requiredKeys(resourceRoot.resolve("values/strings.xml"))
        val localizedDirectories = Files.list(resourceRoot).use { paths ->
            paths.filter { it.fileName.toString().startsWith("values-") && it.fileName.toString() != "values-night" }
                .toList()
        }

        assertEquals(17, localizedDirectories.size)
        assertTrue("Expected PS3 core, firmware and credit strings", required.size >= 14)
        assertTrue(
            "Dead settings returned to the default locale",
            parse(resourceRoot.resolve("values/strings.xml")).keys.intersect(removedDeadKeys).isEmpty()
        )
        localizedDirectories.forEach { directory ->
            assertEquals("PS3 resources differ in ${directory.fileName}", required, requiredKeys(directory.resolve("strings.xml")))
            val localized = parse(directory.resolve("strings.xml"))
            assertTrue(
                "Dead settings returned to ${directory.fileName}",
                localized.keys.intersect(removedDeadKeys).isEmpty()
            )
            assertFalse(
                "Vita branding leaked into ${directory.fileName}",
                localized.values.any(legacyBranding::containsMatchIn)
            )
        }
    }

    @Test
    fun everySupportedLocaleContainsAuditedCoreTitlesValuesAndHelp() {
        val resourceRoot = locateResourceRoot()
        val defaultContract = parse(resourceRoot.resolve("values/strings.xml"))
        val labelKeys = defaultContract.keys.filter { it.startsWith("core_label_") }
        val helpKeys = defaultContract.keys.filter { it.startsWith("core_help_") }

        assertEquals("Unexpected RPCSX/RPCS3 title/value contract", 472, labelKeys.size)
        assertEquals("Every user-facing native option needs audited help", 203, helpKeys.size)
        assertTrue(defaultContract.values.none(String::isBlank))
        assertTrue("Mojibake leaked into default core resources", defaultContract.values.none(::containsMojibake))

        Files.list(resourceRoot).use { paths ->
            paths.filter { it.fileName.toString().startsWith("values-") && it.fileName.toString() != "values-night" }
                .forEach { directory ->
                    val localized = parse(directory.resolve("strings.xml"))
                    assertEquals("Core localization keys differ in ${directory.fileName}", defaultContract.keys, localized.keys)
                    assertTrue("Blank core localization in ${directory.fileName}", localized.values.none(String::isBlank))
                    val corrupted = localized.filterValues(::containsMojibake)
                    assertTrue(
                        "Mojibake leaked into ${directory.fileName}: $corrupted",
                        corrupted.isEmpty(),
                    )
                }
        }
    }

    private fun containsMojibake(value: String): Boolean = mojibakeMarkers.any(value::contains)

    @Test
    fun everyNativeEnumDisplayValueHasAStringResource() {
        val resourceRoot = locateResourceRoot()
        val defaultKeys = parse(resourceRoot.resolve("values/strings.xml")).keys
        // The core sources live under core/rpcs3/. Walk up from the resource
        // root until that tree is found, which keeps this working regardless of
        // the Gradle working directory.
        var repoRoot: Path = resourceRoot
        while (repoRoot.parent != null && !Files.isDirectory(repoRoot.resolve("core/rpcs3"))) {
            repoRoot = repoRoot.parent
        }
        val coreRoot = repoRoot.resolve("core/rpcs3")
        val systemTypes = coreRoot.resolve("Emu/system_config_types.cpp").toFile().readText()
        val keyboardTypes = coreRoot.resolve("Emu/Io/KeyboardHandler.cpp").toFile().readText()
            .substringBefore("fmt_class_string<keyboard_consumer")
        val sysutilFile = sequenceOf(
            coreRoot.resolve("Emu/Cell/Modules/cellSysutil.cpp"),
            repoRoot.resolve("ps3fw/cellSysutil.cpp")
        ).firstOrNull { Files.isRegularFile(it) }?.toFile()
        val sysutilTypes = sysutilFile?.readText()?.let { src ->
            src.substringAfter("fmt_class_string<CellSysutilLang>", "")
                .substringBefore("fmt_class_string<CellSysutilParamId>", "")
        }.orEmpty()
        val nativeLabels = listOf(systemTypes, keyboardTypes, sysutilTypes).flatMap { source ->
            Regex("""return\s+"([^"]+)"""").findAll(source)
                .map { it.groupValues[1] }
                .toList()
        }.toSet()

        nativeLabels.forEach { label ->
            val slug = label.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
            assertTrue("Missing localized native enum value: $label", "core_label_$slug" in defaultKeys)
        }
    }

    private fun requiredKeys(path: Path): Set<String> = parse(path).keys.filterTo(mutableSetOf()) { name ->
        name.startsWith("ps3_core_") ||
            name.startsWith("onboarding_firmware_") ||
            name == "onboarding_status_install_firmware" ||
            name.startsWith("settings_rpcsx_") ||
            name.startsWith("settings_rpcs3_") ||
            name == "settings_core_source_description"
    }

    private fun parse(path: Path): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile()).getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                put(node.attributes?.getNamedItem("name")?.nodeValue.orEmpty(), node.textContent.trim())
            }
        }
    }

    private fun locateResourceRoot(): Path {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        return sequenceOf(workingDirectory.resolve("src/main/res"), workingDirectory.resolve("app/src/main/res"))
            .firstOrNull(Path::isDirectory)
            ?: error("Unable to locate app/src/main/res from $workingDirectory")
    }
}
