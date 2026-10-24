package com.lorran.vitral

/**
 * "Espaços" = temas locais criados pelo próprio app Temas (ex.: "Backup do tema").
 * Eles aplicam sem licença, então o Vitral troca o conteúdo deles pelo do tema novo.
 */
data class Slot(
    val id: String,
    val title: String,
    val modified: Long,
    val size: Long,
    /** resourceCode -> localId do componente */
    val parts: Map<String, String>,
    val local: Boolean,
)

object Slots {
    const val DATA = "/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    const val ORIGINALS = "/sdcard/Download/Vitral/originais"

    /** Lista cada .mrm de tema com a data, separados por um marcador. */
    val dumpCommand = "cd ${Shell.q("$DATA/meta/theme")} 2>/dev/null && for f in *.mrm; do " +
        "[ -f \"\$f\" ] || continue; printf '\\n@@VITRAL@@%s|%s\\n' \"\$f\" \"\$(stat -c %Y \"\$f\")\"; cat \"\$f\"; done"

    fun splitDump(out: String): List<Triple<String, Long, String>> =
        out.split("@@VITRAL@@").drop(1).mapNotNull { chunk ->
            val head = chunk.substringBefore('\n')
            val body = chunk.substringAfter('\n', "")
            val name = head.substringBefore('|').trim()
            val time = head.substringAfter('|', "").trim().toLongOrNull() ?: return@mapNotNull null
            if (!name.endsWith(".mrm")) null else Triple(name, time * 1000, body.trim())
        }

    /** Lê o essencial de um .mrm (JSON) sem depender de bibliotecas. */
    fun parse(name: String, modified: Long, json: String): Slot? {
        val id = str(json, "localId") ?: name.removeSuffix(".mrm")
        val title = Regex("\"titles\"\\s*:\\s*\\{[^}]*?\"fallback\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(json)?.groupValues?.get(1)?.let(::unescape) ?: return null
        val size = Regex("\"size\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val parts = LinkedHashMap<String, String>()
        val subs = json.substringAfter("\"subResources\"", "").substringBefore("]")
        Regex("\\{[^{}]*?\"localId\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"resourceCode\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(subs).forEach { parts.putIfAbsent(it.groupValues[2], it.groupValues[1]) }
        val local = isNull(json, "onlineId") && isNull(json, "productId") && isNull(json, "assemblyId")
        return Slot(id, title, modified, size, parts, local)
    }

    /** Troca "hash" e "size" de um .mrm de componente pelos do arquivo novo. */
    fun patchMeta(json: String, hash: String, size: Long): String = json
        .replaceFirst(Regex("\"hash\"\\s*:\\s*(\"[^\"]*\"|null)"), "\"hash\": \"$hash\"")
        .replaceFirst(Regex("\"size\"\\s*:\\s*\\d+"), "\"size\": $size")

    private fun str(json: String, key: String) =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun isNull(json: String, key: String): Boolean {
        val m = Regex("\"$key\"\\s*:\\s*(null|\"[^\"]*\")").find(json) ?: return true
        return m.groupValues[1] == "null"
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\\\", "\\")
}
