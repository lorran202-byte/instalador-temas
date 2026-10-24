package com.lorran.vitral

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Um arquivo .mtz no celular (backup do app Temas ou cópia de segurança do Vitral). */
data class ThemeFile(val path: String, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast('/')
    val folder: String get() = path.substringBeforeLast('/')
}

/** O que dá para saber de um .mtz antes de instalar. */
data class MtzInfo(
    val title: String?,
    val author: String?,
    val modules: List<String>,
    val preview: ByteArray?,
)

object Themes {
    const val THEMES_PKG = "com.android.thememanager"
    /** Onde o app Temas guarda os backups e temas locais. */
    val THEME_DIRS = listOf(
        "/sdcard/Android/data/$THEMES_PKG/files/MIUI/theme",
        "/sdcard/MIUI/theme",
    )
    const val BACKUP_DIR = "/sdcard/Download/Vitral/backups"

    /** Comando que lista os .mtz com tamanho e data (formato: tamanho|data|caminho). */
    fun listCommand(dirs: List<String>, depth: Int = 2): String {
        val paths = dirs.joinToString(" ") { Shell.q(it) }
        return "find $paths -maxdepth $depth -type f -name '*.mtz' 2>/dev/null | " +
            "while IFS= read -r f; do stat -c '%s|%Y|%n' \"\$f\"; done"
    }

    fun parseList(output: String): List<ThemeFile> = output.lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val size = parts[0].toLongOrNull() ?: return@mapNotNull null
            val time = parts[1].toLongOrNull() ?: return@mapNotNull null
            if (!parts[2].endsWith(".mtz", ignoreCase = true)) return@mapNotNull null
            ThemeFile(parts[2], size, time * 1000)
        }
        .distinctBy { it.path }
        .sortedByDescending { it.modified }
        .toList()

    /** Nome da cópia de segurança: nome original + data, para nunca sobrescrever outra. */
    fun backupName(original: String, stamp: String): String {
        val base = original.removeSuffix(".mtz").replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        return "${base}__$stamp.mtz"
    }

    /** Nome do arquivo original a partir de uma cópia feita pelo Vitral. */
    fun originalName(backup: String): String =
        backup.removeSuffix(".mtz").substringBeforeLast("__") + ".mtz"

    /** Lê o .mtz sem extrair tudo: confere o description.xml, módulos e uma prévia. */
    fun inspect(input: InputStream): MtzInfo? {
        var description: String? = null
        val modules = mutableListOf<String>()
        var preview: ByteArray? = null
        var previewName: String? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.trimStart('/')
                when {
                    name.equals("description.xml", true) ->
                        description = zip.readBytes().toString(Charsets.UTF_8)
                    name.startsWith("preview/", true) && !e.isDirectory &&
                        (name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".webp", true)) -> {
                        // prefere a prévia da tela de bloqueio ou da tela inicial
                        val better = previewName == null ||
                            (name.contains("lock", true) && previewName?.contains("lock", true) != true)
                        if (better) { preview = zip.readBytes(); previewName = name }
                    }
                    !name.contains('/') && !e.isDirectory -> modules += name
                    name.endsWith('/') && name.count { it == '/' } == 1 -> modules += name.trimEnd('/')
                    name.count { it == '/' } >= 1 -> {
                        val top = name.substringBefore('/')
                        if (top !in modules) modules += top
                    }
                }
            }
        }
        val desc = description ?: return null
        return MtzInfo(
            title = tag(desc, "title"),
            author = tag(desc, "designer") ?: tag(desc, "author"),
            modules = modules.filter { it != "description.xml" && it != "preview" }.distinct(),
            preview = preview,
        )
    }

    fun tag(xml: String, name: String): String? {
        val m = Regex("<$name[^>]*>(.*?)</$name>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(xml) ?: return null
        return m.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").trim().ifBlank { null }
    }

    /** Nome amigável dos módulos mais comuns. */
    fun moduleLabel(m: String): String = when (m) {
        "icons" -> "Ícones"
        "lockscreen" -> "Tela de bloqueio"
        "wallpaper" -> "Papel de parede"
        "com.android.systemui" -> "Barra e notificações"
        "miui.systemui.plugin" -> "Central de controle"
        "com.miui.home" -> "Tela inicial"
        "framework-res", "framework-miui-res" -> "Sistema"
        "fonts" -> "Fonte"
        "aod" -> "Tela sempre ativa"
        "clock_2x4" -> "Widget de relógio"
        "miwallpaper" -> "Papel de parede animado"
        "com.android.settings" -> "Configurações"
        "com.android.contacts" -> "Telefone e contatos"
        "com.android.mms" -> "Mensagens"
        else -> m
    }
}
