package com.lorran.vitral

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Picked(val name: String, val size: Long, val info: MtzInfo, val preview: ImageBitmap?)

data class Outcome(val ok: Boolean, val title: String, val message: String)

data class UiState(
    val shizuku: ShizukuState = ShizukuState.PARADO,
    val uid: Int = -1,
    val loading: Boolean = false,
    val targets: List<ThemeFile> = emptyList(),
    val selected: String? = null,
    val backups: List<ThemeFile> = emptyList(),
    val picked: Picked? = null,
    val pickError: String? = null,
    val busy: Boolean = false,
    val steps: List<String> = emptyList(),
    val outcome: Outcome? = null,
    val listError: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val stage get() = File(getApplication<Application>().cacheDir, "novo-tema.mtz")

    fun refresh() = viewModelScope.launch {
        val st = Shell.state(getApplication())
        _ui.update { it.copy(shizuku = st) }
        if (st != ShizukuState.PRONTO) return@launch
        _ui.update { it.copy(loading = true, uid = Shell.uid(), listError = null) }
        val list = Shell.run(Themes.listCommand(Themes.THEME_DIRS))
        val backups = Shell.run(Themes.listCommand(listOf(Themes.BACKUP_DIR), 1))
        val targets = Themes.parseList(list.out)
        _ui.update { s ->
            s.copy(
                loading = false,
                targets = targets,
                selected = s.selected?.takeIf { sel -> targets.any { it.path == sel } } ?: targets.firstOrNull()?.path,
                backups = Themes.parseList(backups.out),
                listError = if (list.code == -1) list.err else null,
            )
        }
    }

    fun select(path: String) = _ui.update { it.copy(selected = path) }

    fun pick(uri: Uri) = viewModelScope.launch {
        _ui.update { it.copy(pickError = null) }
        val app = getApplication<Application>()
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val cr = app.contentResolver
                var name = "tema.mtz"
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                cr.openInputStream(uri)!!.use { input -> stage.outputStream().use { input.copyTo(it) } }
                val info = stage.inputStream().buffered().use { Themes.inspect(it) }
                    ?: error("Esse arquivo não parece um tema: não tem o description.xml.")
                val bmp = info.preview?.let { bytes ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
                }
                Picked(name, stage.length(), info, bmp)
            }
        }
        result.onSuccess { p -> _ui.update { it.copy(picked = p) } }
            .onFailure { e ->
                stage.delete()
                val msg = if (e is java.util.zip.ZipException) "O arquivo está corrompido ou não é um .mtz."
                else e.message ?: "Não consegui ler o arquivo."
                _ui.update { it.copy(picked = null, pickError = msg) }
            }
    }

    private fun step(text: String) = _ui.update { it.copy(steps = it.steps + text) }

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Troca o backup escolhido pelo tema novo, guardando antes uma cópia do original. */
    fun install() = viewModelScope.launch {
        val s = _ui.value
        val target = s.selected ?: return@launch
        if (s.picked == null || !stage.exists()) return@launch
        _ui.update { it.copy(busy = true, steps = emptyList(), outcome = null) }
        val q = Shell::q
        val name = target.substringAfterLast('/')

        step("Guardando uma cópia do backup original…")
        val copy = "${Themes.BACKUP_DIR}/${Themes.backupName(name, stamp())}"
        val r1 = Shell.run("mkdir -p ${q(Themes.BACKUP_DIR)} && cp ${q(target)} ${q(copy)}")
        if (!r1.ok) return@launch fail("Não consegui copiar o backup original.", r1)

        step("Colocando o tema novo no lugar do backup…")
        val r2 = Shell.run("cat > ${q(target)}", stage.inputStream())
        if (!r2.ok) return@launch fail("Não consegui gravar o tema na pasta do app Temas.", r2)

        step("Conferindo o arquivo…")
        val r3 = Shell.run("stat -c '%s' ${q(target)}")
        if (r3.out.toLongOrNull() != stage.length()) {
            Shell.run("cat ${q(copy)} > ${q(target)}")
            return@launch fail("O arquivo gravado ficou diferente do original. Desfiz a troca.", r3)
        }

        step("Reiniciando o app Temas para ele ler o tema novo…")
        Shell.run("am force-stop ${Themes.THEMES_PKG}")

        _ui.update {
            it.copy(
                busy = false,
                outcome = Outcome(
                    true, "Tema instalado",
                    "Abra o app Temas, entre no backup \"${name.removeSuffix(".mtz")}\" e toque em Aplicar. " +
                        "A cópia do backup original ficou em Download/Vitral/backups."
                ),
            )
        }
        refresh()
    }

    /** Devolve uma cópia de segurança para o lugar de onde ela saiu. */
    fun restore(backup: ThemeFile) = viewModelScope.launch {
        val original = Themes.originalName(backup.name)
        val dest = _ui.value.targets.firstOrNull { it.name == original }?.path
            ?: "${Themes.THEME_DIRS.first()}/$original"
        _ui.update { it.copy(busy = true, steps = listOf("Restaurando $original…"), outcome = null) }
        val q = Shell::q
        val r = Shell.run("mkdir -p ${q(dest.substringBeforeLast('/'))} && cat ${q(backup.path)} > ${q(dest)} && am force-stop ${Themes.THEMES_PKG}")
        if (!r.ok) return@launch fail("Não consegui restaurar o backup.", r)
        _ui.update {
            it.copy(busy = false, outcome = Outcome(true, "Backup restaurado",
                "O backup original voltou para o app Temas. Abra o Temas e aplique se quiser voltar ao tema antigo."))
        }
        refresh()
    }

    private fun fail(msg: String, r: ShellResult) {
        val detail = (r.err.ifBlank { r.out }).take(300)
        _ui.update {
            it.copy(busy = false, outcome = Outcome(false, "Não deu certo", if (detail.isBlank()) msg else "$msg\n\nDetalhe: $detail"))
        }
    }

    fun dismissOutcome() = _ui.update { it.copy(outcome = null) }
}
