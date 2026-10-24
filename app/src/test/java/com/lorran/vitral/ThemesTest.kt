package com.lorran.vitral

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemesTest {

    @Test fun quoteHandlesSpacesAndApostrophes() {
        assertEquals("'a b'", Shell.q("a b"))
        assertEquals("'it'\\''s'", Shell.q("it's"))
    }

    @Test fun parsesStatOutputNewestFirst() {
        val out = """
            1200|1700000000|/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/Backup antigo.mtz
            900|1800000000|/sdcard/MIUI/theme/novo.mtz
            lixo
            12|abc|/x.mtz
            5|1|/sdcard/a.zip
        """.trimIndent()
        val list = Themes.parseList(out)
        assertEquals(2, list.size)
        assertEquals("novo.mtz", list[0].name)
        assertEquals("Backup antigo.mtz", list[1].name)
        assertEquals(1700000000000L, list[1].modified)
    }

    @Test fun backupNameRoundTrips() {
        val b = Themes.backupName("Meu tema (1).mtz", "20261024-130000")
        assertTrue(b.endsWith("__20261024-130000.mtz"))
        assertEquals("Meu tema _1_.mtz", Themes.originalName(b))
    }

    @Test fun inspectReadsDescriptionAndModules() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("description.xml"))
                z.write("<theme><title><![CDATA[Liquid Glass OS]]></title><designer>Lorran</designer></theme>".toByteArray())
                z.putNextEntry(ZipEntry("icons")); z.write(byteArrayOf(1, 2, 3))
                z.putNextEntry(ZipEntry("lockscreen")); z.write(byteArrayOf(1))
                z.putNextEntry(ZipEntry("wallpaper/default_wallpaper.jpg")); z.write(byteArrayOf(9))
                z.putNextEntry(ZipEntry("preview/preview_launcher_0.jpg")); z.write(byteArrayOf(7))
                z.putNextEntry(ZipEntry("preview/preview_lockscreen_0.jpg")); z.write(byteArrayOf(8))
            }
        }.toByteArray()
        val info = Themes.inspect(ByteArrayInputStream(bytes))!!
        assertEquals("Liquid Glass OS", info.title)
        assertEquals("Lorran", info.author)
        assertEquals(listOf("icons", "lockscreen", "wallpaper"), info.modules)
        assertArrayEquals(byteArrayOf(8), info.preview)
    }

    @Test fun inspectRejectsZipWithoutDescription() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("foo.txt")); z.write(1) }
        }.toByteArray()
        assertNull(Themes.inspect(ByteArrayInputStream(bytes)))
    }

    @Test fun listCommandQuotesDirs() {
        val cmd = Themes.listCommand(listOf("/sdcard/a b"), 1)
        assertTrue(cmd.startsWith("find '/sdcard/a b' -maxdepth 1"))
    }
}
