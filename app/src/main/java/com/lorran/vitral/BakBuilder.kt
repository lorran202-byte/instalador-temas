package com.lorran.vitral

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Monta um backup local do HyperOS ("Backup e restauração") só com o app Temas,
 * contendo um tema .mtz já registrado como tema local.
 *
 * Estrutura do .bak: cabeçalho de texto do MIUI + cabeçalho "ANDROID BACKUP" sem
 * compressão + arquivo tar. Dentro do tar, "apps/<pacote>/ef/" é a pasta
 * Android/data/<pacote>/files do app.
 */
object BakBuilder {
    const val PKG = "com.android.thememanager"
    const val BAK_NAME = "Temas($PKG).bak"
    private const val BLOCK = 512

    /** Dados do app Temas instalado, exigidos pelo manifesto do backup Android. */
    data class Host(
        val versionCode: Long,
        val sdk: Int,
        val installer: String,
        val signatures: List<String>,
        val device: String,
        val osVersion: String,
    )

    data class Result(val zipName: String, val bakSize: Long, val title: String)

    /** Gera o .zip (descript.xml + .bak) em [outZip]. */
    fun build(mtz: File, host: Host, workDir: File, outZip: File, now: Long = System.currentTimeMillis()): Result {
        require(host.signatures.isNotEmpty()) { "Não consegui ler a assinatura do app Temas." }
        workDir.mkdirs()
        val bak = File(workDir, "tema.bak")
        val title = bak.outputStream().buffered(256 * 1024).use { writeBak(mtz, host, it, now) }
        val descript = descript(host, bak.length(), now)
        ZipOutputStream(outZip.outputStream().buffered(256 * 1024)).use { z ->
            stored(z, "descript.xml", descript.toByteArray())
            storedFile(z, BAK_NAME, bak)
        }
        val size = bak.length()
        bak.delete()
        return Result(outZip.name, size, title)
    }

    fun zipName(now: Long): String =
        java.text.SimpleDateFormat("ddMMyyyy_HHmmss", java.util.Locale.US).format(java.util.Date(now)) + ".zip"

    /** Escreve o .bak e devolve o título do tema. */
    fun writeBak(mtz: File, host: Host, out: OutputStream, now: Long): String {
        out.write("MIUI BACKUP\n2\n$PKG Temas\n-1\n0\nANDROID BACKUP\n5\n0\nnone\n".toByteArray())
        val tar = TarWriter(out, now / 1000)
        val base = "apps/$PKG"
        tar.file("$base/_manifest", manifest(host))

        val desc = ZipFile(mtz).use { z -> z.getEntry("description.xml")?.let { e -> z.getInputStream(e).readBytes().toString(Charsets.UTF_8) } } ?: ""
        val title = Themes.tag(desc, "title") ?: mtz.nameWithoutExtension
        val author = Themes.tag(desc, "designer") ?: Themes.tag(desc, "author") ?: "Vitral"
        val version = Themes.tag(desc, "version") ?: "1.0"
        val about = Themes.tag(desc, "description") ?: ""
        val themeHash = sha1(mtz)
        val themeId = uuid("theme:$themeHash")
        val assembly = uuid("assembly:$themeId")
        val root = "$base/ef/MIUI/theme"

        ZipFile(mtz).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val parts = entries.mapNotNull { e ->
                val code = componentCode(e.name) ?: return@mapNotNull null
                if (e.name.contains('/') && !e.name.startsWith("wallpaper/")) return@mapNotNull null
                if (!e.name.contains('/') && !isZip(zip, e)) return@mapNotNull null
                val hash = zip.getInputStream(e).use { sha1(it) }
                Part(e, code, uuid("$themeId:$code:$hash"), e.size, hash)
            }.distinctBy { it.code }

            val previews = entries.map { it.name }.filter { it.startsWith("preview/") }
                .map { it.substringAfterLast('/') }.filter { it.isNotBlank() }
            val meta = Meta(title, author, version, about)
            tar.file("$root/.data/meta/theme/$themeId.mrm",
                metaJson(meta, themeId, assembly, themeHash, mtz.length(), null, parts, previews).toByteArray())
            tar.file("$root/.data/content/theme/$themeId.mrc", ByteArray(0))
            for (p in parts) {
                tar.file("$root/.data/meta/${p.code}/${p.id}.mrm",
                    metaJson(meta, p.id, assembly, p.hash, p.size, themeId, emptyList(), emptyList()).toByteArray())
                zip.getInputStream(p.entry).use { tar.stream("$root/.data/content/${p.code}/${p.id}.mrc", p.size, it) }
            }
            entries.filter { it.name.startsWith("preview/") }.forEach { e ->
                val name = e.name.removePrefix("preview/").substringAfterLast('/')
                if (name.isNotBlank()) zip.getInputStream(e).use { tar.stream("$root/.data/preview/theme/$themeId/$name", e.size, it) }
            }
        }
        mtz.inputStream().use { tar.stream("$root/${fileName(title)}", mtz.length(), it) }
        tar.finish()
        return title
    }

    data class Part(val entry: ZipEntry, val code: String, val id: String, val size: Long, val hash: String)
    data class Meta(val title: String, val author: String, val version: String, val about: String)

    /** Código de recurso que o app Temas usa para cada módulo do .mtz. */
    fun componentCode(name: String): String? {
        val stem = name.substringBeforeLast('.')
        return when {
            stem == "wallpaper/default_wallpaper" -> "wallpaper"
            stem == "wallpaper/default_lock_wallpaper" -> "lockscreen"
            name.contains('/') -> null
            name == "description.xml" -> null
            name == "lockscreen" -> "lockstyle"
            name == "com.android.systemui" -> "statusbar"
            name == "com.miui.home" -> "launcher"
            name == "com.android.contacts" -> "contact"
            name == "com.android.mms" -> "mms"
            else -> name
        }
    }

    /** Manifesto padrão do backup Android (versão 1). */
    fun manifest(h: Host): ByteArray = buildString {
        append("1\n").append(PKG).append('\n').append(h.versionCode).append('\n').append(h.sdk).append('\n')
        append(h.installer).append('\n').append("0\n").append(h.signatures.size).append('\n')
        h.signatures.forEach { append(it).append('\n') }
    }.toByteArray()

    /**
     * Registro do tema no mesmo formato dos temas locais que o app Temas já aceita
     * (price -1 = tema local/grátis, isBackUpVersion false). Com price 0 e
     * isBackUpVersion true o app tentava baixar a licença e falhava (downloadRight|413).
     */
    fun metaJson(m: Meta, id: String, assembly: String, hash: String, size: Long,
                 parent: String?, children: List<Part>, previews: List<String>): String {
        val parents = if (parent == null) "" else
            """{"localId":${j(parent)},"resourceCode":"theme","extraMeta":{},"metaPath":null,"contentPath":null}"""
        val subs = children.joinToString(",") {
            """{"localId":${j(it.id)},"resourceCode":${j(it.code)},"extraMeta":{},"metaPath":null,"contentPath":null}"""
        }
        val thumbs = previews.joinToString(",") { j(it) }
        return """{"localId":${j(id)},"onlineId":null,"assemblyId":${j(assembly)},"productId":null,""" +
            """"hash":${j(hash)},"platform":17,"size":$size,"updatedTime":0,"version":${j(m.version)},""" +
            """"authors":{"fallback":${j(m.author)}},"designers":{"fallback":${j(m.author)}},""" +
            """"titles":{"fallback":${j(m.title)}},"descriptions":{"fallback":${j(m.about)}},""" +
            """"builtInThumbnails":{"fallback":[$thumbs]},"builtInPreviews":{"fallback":[$thumbs]},""" +
            """"thumbnails":[],"previews":[],"parentResources":[$parents],"subResources":[$subs],""" +
            """"extraMeta":{},"metaPath":null,"contentPath":null,"rightsPath":null,"screenRatio":null,""" +
            """"supportHomeSearchBar":false,"packageVersion":null,"packageName":null,"officialIcons":false,""" +
            """"iconsCount":null,"fontWeight":null,"price":-1,"isBackUpVersion":false,"themeType":0,""" +
            """"miuiAdapterVersion":null}"""
    }

    fun descript(h: Host, bakSize: Long, now: Long): String =
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><MIUI-backup><jsonMsg></jsonMsg>" +
            "<bakVersion>2</bakVersion><brState>3</brState><autoBackup>false</autoBackup>" +
            "<device>${x(h.device)}</device><miuiVersion>${x(h.osVersion)}</miuiVersion><date>$now</date>" +
            "<size>0</size><storageLeft>0</storageLeft><supportReconnect>true</supportReconnect>" +
            "<autoRetransferCnt>0</autoRetransferCnt><transRealCompletedSize>0</transRealCompletedSize>" +
            "<packages><package><packageName>$PKG</packageName><feature>-1</feature><bakFile>${x(BAK_NAME)}</bakFile>" +
            "<splitFile></splitFile><splitFileSize>0</splitFileSize><bakType>1</bakType><pkgSize>$bakSize</pkgSize>" +
            "<sdSize>0</sdSize><state>1</state><completedSize>$bakSize</completedSize><error>0</error><progType>0</progType>" +
            "<bakFileSize>0</bakFileSize><transingCompletedSize>0</transingCompletedSize><transingTotalSize>$bakSize</transingTotalSize>" +
            "<transingSdCompletedSize>0</transingSdCompletedSize><sectionSize>0</sectionSize><sendingIndex>0</sendingIndex>" +
            "</package></packages><filesModifyTime /></MIUI-backup>"

    fun fileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "tema" }.take(60) + ".mtz"

    private fun isZip(z: ZipFile, e: ZipEntry): Boolean = z.getInputStream(e).use { s ->
        val b = ByteArray(4); s.read(b) == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
    }

    private fun uuid(seed: String) = UUID.nameUUIDFromBytes(seed.toByteArray()).toString()
    private fun j(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
        append('"')
    }
    private fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun sha1(f: File) = f.inputStream().use { sha1(it) }
    private fun sha1(input: java.io.InputStream): String {
        val d = MessageDigest.getInstance("SHA-1"); val buf = ByteArray(64 * 1024)
        while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    private fun stored(z: ZipOutputStream, name: String, data: ByteArray) {
        val crc = CRC32().apply { update(data) }
        z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = size; this.crc = crc.value })
        z.write(data); z.closeEntry()
    }
    private fun storedFile(z: ZipOutputStream, name: String, f: File) {
        val crc = CRC32(); f.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n < 0) break; crc.update(b, 0, n) } }
        z.putNextEntry(ZipEntry(name).apply { method = ZipEntry.STORED; size = f.length(); compressedSize = size; this.crc = crc.value })
        f.inputStream().use { it.copyTo(z, 64 * 1024) }; z.closeEntry()
    }
}

/** Escritor tar (ustar) mínimo, com suporte a caminhos longos pelo campo "prefix". */
class TarWriter(private val out: OutputStream, private val mtime: Long) {
    fun file(path: String, data: ByteArray) { header(path, data.size.toLong()); out.write(data); pad(data.size.toLong()) }

    fun stream(path: String, size: Long, input: java.io.InputStream) {
        header(path, size)
        var left = size; val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("Arquivo terminou antes do esperado: $path")
            out.write(buf, 0, n); left -= n
        }
        pad(size)
    }

    fun finish() = out.write(ByteArray(BLOCK * 2))

    private fun header(path: String, size: Long) {
        val h = ByteArray(BLOCK)
        val (prefix, name) = split(path)
        name.copyInto(h, 0)
        num(h, 100, 8, 420)          // modo 0644
        num(h, 108, 8, 1000); num(h, 116, 8, 1000)
        num(h, 124, 12, size); num(h, 136, 12, mtime)
        h[156] = '0'.code.toByte()
        "ustar".toByteArray().copyInto(h, 257); "00".toByteArray().copyInto(h, 263)
        prefix.copyInto(h, 345)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        val chk = sum.toString(8).padStart(6, '0').toByteArray()
        chk.copyInto(h, 148); h[154] = 0; h[155] = ' '.code.toByte()
        out.write(h)
    }

    private fun split(path: String): Pair<ByteArray, ByteArray> {
        val full = path.toByteArray()
        if (full.size <= 100) return ByteArray(0) to full
        for (i in path.indices.reversed()) {
            if (path[i] != '/') continue
            val p = path.substring(0, i).toByteArray(); val n = path.substring(i + 1).toByteArray()
            if (p.size <= 155 && n.size <= 100) return p to n
        }
        error("Caminho longo demais para o tar: $path")
    }

    private fun num(h: ByteArray, at: Int, len: Int, v: Long) {
        v.toString(8).padStart(len - 1, '0').toByteArray().copyInto(h, at); h[at + len - 1] = 0
    }

    private fun pad(size: Long) { val r = (size % BLOCK).toInt(); if (r != 0) out.write(ByteArray(BLOCK - r)) }

    companion object { const val BLOCK = 512 }
}
