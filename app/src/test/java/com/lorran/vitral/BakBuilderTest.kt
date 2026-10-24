package com.lorran.vitral

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class BakBuilderTest {
    private val host = BakBuilder.Host(123, 36, "", listOf("abcd"), "rodin", "OS3.0.302.0.WOJMIXM")

    private fun innerZip(): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("res/a.png")); z.write(ByteArray(700) { 1 }) }
    }.toByteArray()

    private fun sampleMtz(dir: File): File {
        val f = File(dir, "tema.mtz")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("description.xml"))
            z.write("<theme><title><![CDATA[Liquid Glass OS]]></title><author>Lorran</author></theme>".toByteArray())
            z.putNextEntry(ZipEntry("icons")); z.write(innerZip())
            z.putNextEntry(ZipEntry("lockscreen")); z.write(innerZip())
            z.putNextEntry(ZipEntry("wallpaper/default_wallpaper.jpg")); z.write(ByteArray(1000) { 2 })
            z.putNextEntry(ZipEntry("preview/preview_lockscreen_0.jpg")); z.write(ByteArray(300) { 3 })
        }
        return f
    }

    /** Lê o tar do .bak e confere o checksum de cada cabeçalho. */
    private fun tarEntries(bak: ByteArray): Map<String, Int> {
        val marker = "none\n".toByteArray()
        var pos = (0..bak.size - marker.size).first { i -> marker.indices.all { bak[i + it] == marker[it] } } + marker.size
        val out = LinkedHashMap<String, Int>()
        while (pos + 512 <= bak.size) {
            val h = bak.copyOfRange(pos, pos + 512)
            if (h.all { it == 0.toByte() }) break
            val sum = h.indices.sumOf { i -> if (i in 148 until 156) 32 else h[i].toInt() and 0xFF }
            val stored = String(h, 148, 6).trim().toInt(8)
            assertEquals("checksum", stored, sum)
            assertEquals("ustar", String(h, 257, 5))
            val name = String(h, 0, 100).trimEnd('\u0000')
            val prefix = String(h, 345, 155).trimEnd('\u0000')
            val size = String(h, 124, 11).toInt(8)
            out[if (prefix.isEmpty()) name else "$prefix/$name"] = size
            pos += 512 + ((size + 511) / 512) * 512
        }
        return out
    }

    @Test fun buildsZipWithDescriptAndBak() {
        val dir = Files.createTempDirectory("vitral").toFile()
        val mtz = sampleMtz(dir)
        val out = File(dir, "out.zip")
        val r = BakBuilder.build(mtz, host, File(dir, "work"), out, 1_792_861_272_659)
        assertEquals("Liquid Glass OS", r.title)
        assertTrue(r.bakSize > 0)

        ZipFile(out).use { z ->
            val desc = z.getInputStream(z.getEntry("descript.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue(desc.contains("<bakFile>Temas(com.android.thememanager).bak</bakFile>"))
            assertTrue(desc.contains("<pkgSize>${r.bakSize}</pkgSize>"))
            assertTrue(desc.contains("<device>rodin</device>"))
            val bak = z.getInputStream(z.getEntry(BakBuilder.BAK_NAME)).readBytes()
            assertEquals(r.bakSize, bak.size.toLong())
            assertTrue(String(bak, 0, 120).startsWith("MIUI BACKUP\n2\ncom.android.thememanager Temas\n-1\n0\nANDROID BACKUP\n5\n0\nnone\n"))

            val entries = tarEntries(bak)
            val root = "apps/com.android.thememanager/ef/MIUI/theme"
            assertEquals("apps/com.android.thememanager/_manifest", entries.keys.first())
            assertTrue(entries.keys.any { it.startsWith("$root/.data/meta/theme/") && it.endsWith(".mrm") })
            assertTrue(entries.keys.any { it.startsWith("$root/.data/content/icons/") })
            assertTrue(entries.keys.any { it.startsWith("$root/.data/content/lockstyle/") })
            assertTrue(entries.keys.any { it.startsWith("$root/.data/content/wallpaper/") })
            assertTrue(entries.keys.any { it.startsWith("$root/.data/preview/theme/") && it.endsWith("preview_lockscreen_0.jpg") })
            assertEquals(mtz.length().toInt(), entries["$root/Liquid Glass OS.mtz"])
        }
    }

    @Test fun manifestListsSignatures() {
        val m = String(BakBuilder.manifest(host))
        assertEquals("1\ncom.android.thememanager\n123\n36\n\n0\n1\nabcd\n", m)
    }

    @Test fun componentCodes() {
        assertEquals("lockstyle", BakBuilder.componentCode("lockscreen"))
        assertEquals("lockscreen", BakBuilder.componentCode("wallpaper/default_lock_wallpaper.jpg"))
        assertEquals("statusbar", BakBuilder.componentCode("com.android.systemui"))
        assertNull(BakBuilder.componentCode("preview/a.jpg"))
        assertNull(BakBuilder.componentCode("description.xml"))
    }

    @Test fun longPathsUsePrefix() {
        val bo = ByteArrayOutputStream()
        val long = "apps/com.android.thememanager/ef/MIUI/theme/.data/preview/theme/123e4567-e89b-12d3-a456-426614174000/preview_lockscreen_0.jpg"
        TarWriter(bo, 0).apply { file(long, byteArrayOf(1)); finish() }
        val bytes = "MIUI BACKUP\nnone\n".toByteArray() + bo.toByteArray()
        assertEquals(setOf(long), tarEntries(bytes).keys)
    }
}
