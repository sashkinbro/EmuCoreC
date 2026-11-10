package com.sbro.emucorec.ui.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingHelpQualityTest {
    @Test
    fun everyLocaleIncludesCompleteHelpAndSectionTitles() {
        val locales = resources()
        val required = locales.getValue("values").keys.filterTo(mutableSetOf()) {
            it.startsWith("core_help_") || it.startsWith("settings_help_") || it.startsWith("settings_group_")
        }
        assertEquals(18, locales.size)
        locales.forEach { (locale, strings) ->
            assertEquals("Missing help in $locale", required, strings.keys.intersect(required))
            required.forEach { key -> assertTrue("Empty $locale/$key", strings.getValue(key).isNotBlank()) }
        }
    }

    @Test
    fun descriptionsExplainMoreThanTheirOptionTitles() {
        fun normalize(value: String) = value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)
        resources().forEach { (locale, strings) ->
            val titles = strings.filterKeys {
                it.startsWith("core_label_") || (it.startsWith("settings_") && !it.startsWith("settings_help_"))
            }.values.map(::normalize).toSet()
            strings.filterKeys { it.startsWith("core_help_") }.forEach { (key, help) ->
                assertFalse("Title repeated as help: $locale/$key", normalize(help) in titles)
            }
        }
    }

    @Test
    fun distinctOptionsDoNotInheritAnUnrelatedCopiedExplanation() {
        val distinctPairs = listOf(
            "core_help_core_spu_cache" to "core_help_core_spu_decoder",
            "core_help_video_screen_size_in_inches" to "core_help_video_3d_display_mode",
            "core_help_video_custom_anaglyph_matrices_matrix_left" to "core_help_video_3d_display_mode",
            "core_help_audio_microphone_devices" to "core_help_audio_microphone_type",
            "core_help_video_read_color_buffers" to "core_help_video_read_depth_buffer",
            "core_help_video_performance_overlay_framerate_graph_detail_level" to "core_help_video_performance_overlay_frametime_graph_detail_level",
            "settings_help_overlay_scale" to "settings_help_overlay_opacity",
        )
        resources().forEach { (locale, strings) ->
            distinctPairs.forEach { (left, right) ->
                assertFalse("Unrelated help copied in $locale: $left = $right", strings.getValue(left) == strings.getValue(right))
            }
            assertFalse("SPU Cache must not recommend the unavailable ASMJIT backend in $locale",
                strings.getValue("core_help_core_spu_cache").contains("ASMJIT", ignoreCase = true))
        }
    }

    private fun resources(): Map<String, Map<String, String>> {
        val directory = Path.of(System.getProperty("user.dir"))
        val root = sequenceOf(directory.resolve("src/main/res"), directory.resolve("app/src/main/res"))
            .first { Files.isDirectory(it) }
        return Files.list(root).use { paths ->
            paths.filter { it.fileName.toString() == "values" ||
                (it.fileName.toString().startsWith("values-") && it.fileName.toString() != "values-night")
            }.toList().associate { locale ->
                val entries = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(locale.resolve("strings.xml").toFile()).getElementsByTagName("string")
                locale.fileName.toString() to buildMap {
                    for (index in 0 until entries.length) {
                        val entry = entries.item(index)
                        put(entry.attributes.getNamedItem("name").nodeValue, entry.textContent.trim())
                    }
                }
            }
        }
    }
}
