package com.lorran.vitral

import android.app.Application
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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

data class Outcome(val ok: Boolean, val title: String, val message: String, val openBackup: Boolean = false)

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

    /** Lê os dados do app Temas instalado que vão no manifesto do backup. */
    private fun host(): BakBuilder.Host {
        val pm = getApplication<Application>().packageManager
        val pkg = BakBuilder.PKG
        val info = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo
        val sigs = when {
            signing == null -> emptyList()
            signing.hasMultipleSigners() -> signing.apkContentsSigners.map { it.toCharsString() }
            else -> signing.signingCertificateHistory.takeLast(1).map { it.toCharsString() }
        }
        val installer = runCatching { pm.getInstallSourceInfo(pkg).installingPackageName ?: "" }.getOrDefault("")
        return BakBuilder.Host(
            versionCode = info.longVersionCode, sdk = Build.VERSION.SDK_INT, installer = installer,
            signatures = sigs, device = Build.DEVICE, osVersion = Build.VERSION.INCREMENTAL,
        )
    }

    /** Transforma o .mtz escolhido num backup do app Temas para restaurar pelas Configurações. */
    fun buildBackup() = viewModelScope.launch {
        if (_ui.value.picked == null || !stage.exists()) return@launch
        val app = getApplication<Application>()
        _ui.update { it.copy(busy = true, steps = listOf("Lendo os dados do app Temas…"), outcome = null) }
        val now = System.currentTimeMillis()
        val zipName = BakBuilder.zipName(now)
        val out = File(app.cacheDir, zipName)
        val built = withContext(Dispatchers.IO) {
            runCatching {
                val h = host()
                step("Montando o backup com o tema…")
                BakBuilder.build(stage, h, File(app.cacheDir, "bak"), out, now)
            }
        }
        built.onFailure { e ->
            out.delete()
            val msg = if (e is PackageManager.NameNotFoundException) "Não encontrei o app Temas neste celular."
            else e.message ?: e.javaClass.simpleName
            _ui.update { it.copy(busy = false, outcome = Outcome(false, "Não deu certo", "Não consegui montar o backup.\n\nDetalhe: $msg")) }
            return@launch
        }

        val restoreSteps = "Agora vá em Configurações > Sobre o telefone > Fazer backup e restaurar > Restaurar, " +
            "escolha o backup $zipName e restaure. Depois abra o app Temas e aplique o tema."
        val warning = "\n\nAtenção: restaurar troca os dados do app Temas. Guarde o seu backup original para voltar se precisar."

        if (_ui.value.shizuku == ShizukuState.PRONTO) {
            step("Colocando o backup na pasta MIUI/backup/AllBackup…")
            val q = Shell::q
            val dir = "/sdcard/MIUI/backup/AllBackup"
            val r = Shell.run("mkdir -p ${q(dir)} && cat > ${q("$dir/$zipName")}", out.inputStream())
            if (r.ok) {
                out.delete()
                _ui.update { it.copy(busy = false, outcome = Outcome(true, "Backup pronto", restoreSteps + warning, openBackup = true)) }
                return@launch
            }
            step("Sem acesso pelo Shizuku, salvando em Downloads…")
        }

        step("Salvando em Download/Vitral…")
        val saved = withContext(Dispatchers.IO) { runCatching { saveToDownloads(out, zipName) } }
        out.delete()
        saved.onSuccess {
            _ui.update {
                it.copy(busy = false, outcome = Outcome(true, "Backup pronto",
                    "O arquivo $zipName ficou em Download/Vitral. Mova ele para a pasta MIUI > backup > AllBackup " +
                        "com o gerenciador de arquivos. $restoreSteps$warning", openBackup = true))
            }
        }.onFailure { e ->
            _ui.update { it.copy(busy = false, outcome = Outcome(false, "Não deu certo", "Não consegui salvar o backup.\n\nDetalhe: ${e.message}")) }
        }
    }

    private fun saveToDownloads(file: File, name: String) {
        val cr = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vitral")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Sem acesso à pasta Download.")
        cr.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(uri, values, null, null)
    }

    private fun fail(msg: String, r: ShellResult) {
        val detail = (r.err.ifBlank { r.out }).take(300)
        _ui.update {
            it.copy(busy = false, outcome = Outcome(false, "Não deu certo", if (detail.isBlank()) msg else "$msg\n\nDetalhe: $detail"))
        }
    }

    fun dismissOutcome() = _ui.update { it.copy(outcome = null) }
}
