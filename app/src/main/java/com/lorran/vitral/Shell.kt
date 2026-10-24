package com.lorran.vitral

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.InputStream

/** Resultado de um comando no shell do Shizuku. */
data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

enum class ShizukuState { NAO_INSTALADO, PARADO, SEM_PERMISSAO, PRONTO, VERSAO_ANTIGA }

/** Envolve o Shizuku: estado, permissão e execução de comandos como "shell". */
object Shell {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val PERMISSION_CODE = 4242

    fun state(context: Context): ShizukuState {
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true
        } catch (_: PackageManager.NameNotFoundException) { false }
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) return if (installed) ShizukuState.PARADO else ShizukuState.NAO_INSTALADO
        if (Shizuku.isPreV11()) return ShizukuState.VERSAO_ANTIGA
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
            ShizukuState.PRONTO else ShizukuState.SEM_PERMISSAO
    }

    fun requestPermission() = Shizuku.requestPermission(PERMISSION_CODE)

    /** UID do serviço: 0 = root, 2000 = ADB (depuração sem fio). */
    fun uid(): Int = try { Shizuku.getUid() } catch (_: Throwable) { -1 }

    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    private fun start(cmd: String): Process =
        newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process

    /** Executa [cmd] no shell; se [input] vier, é enviado para a entrada padrão. */
    suspend fun run(cmd: String, input: InputStream? = null): ShellResult = withContext(Dispatchers.IO) {
        try {
            val p = start(cmd)
            if (input != null) {
                p.outputStream.use { out -> input.use { it.copyTo(out, 256 * 1024) } }
            } else {
                p.outputStream.close()
            }
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            ShellResult(p.waitFor(), out.trim(), err.trim())
        } catch (t: Throwable) {
            val cause = (t.cause ?: t)
            ShellResult(-1, "", cause.message ?: cause.javaClass.simpleName)
        }
    }

    /** Coloca um texto entre aspas simples para o shell (aceita espaços e acentos). */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
