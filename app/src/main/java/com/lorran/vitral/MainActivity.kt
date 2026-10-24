package com.lorran.vitral

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rikka.shizuku.Shizuku

private val Fundo1 = Color(0xFF0B1026)
private val Fundo2 = Color(0xFF241046)
private val Vidro = Color(0x1FFFFFFF)
private val Borda = Color(0x33FFFFFF)
private val Texto2 = Color(0xB3FFFFFF)
private val Destaque = Color(0xFF7C9CFF)
private val Ok = Color(0xFF5BE49B)
private val Alerta = Color(0xFFFFB454)

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val binderListener = Shizuku.OnBinderReceivedListener { vm.refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener { vm.refresh() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> vm.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Destaque, background = Fundo1, surface = Color(0xFF161B38))) {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.pick(uri)
    }
}

fun openThemes(context: Context) {
    val pkg = Themes.THEMES_PKG
    val tries = listOf(
        Intent().setComponent(ComponentName(pkg, "com.android.thememanager.mine.remote.view.activity.MineResourceTabActivity")),
        context.packageManager.getLaunchIntentForPackage(pkg),
    )
    for (i in tries) {
        if (i == null) continue
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

fun openUrl(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}

@Composable
fun App(vm: MainViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::pick) }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Fundo1, Fundo2)))
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Vitral", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Instale temas .mtz no HyperOS sem root", color = Texto2, fontSize = 15.sp)

            ShizukuCard(s, ctx)

            if (s.shizuku == ShizukuState.PRONTO) {
                ThemeCard(s, onPick = { picker.launch(arrayOf("*/*")) })
                TargetCard(s, vm, ctx)
                InstallButton(s, vm)
                if (s.steps.isNotEmpty()) StepsCard(s)
                if (s.backups.isNotEmpty()) RestoreCard(s, vm)
            }
            Spacer(Modifier.height(24.dp))
        }

        s.outcome?.let { o ->
            AlertDialog(
                onDismissRequest = vm::dismissOutcome,
                icon = { Icon(if (o.ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (o.ok) Ok else Alerta) },
                title = { Text(o.title) },
                text = { Text(o.message) },
                confirmButton = {
                    if (o.ok) TextButton(onClick = { vm.dismissOutcome(); openThemes(ctx) }) { Text("Abrir Temas") }
                    else TextButton(onClick = vm::dismissOutcome) { Text("Entendi") }
                },
                dismissButton = { if (o.ok) TextButton(onClick = vm::dismissOutcome) { Text("Fechar") } },
            )
        }
    }
}

@Composable
fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Vidro)
            .border(1.dp, Borda, RoundedCornerShape(24.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun CardTitle(icon: ImageVector, text: String, tint: Color = Color.White) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White)
    }
}

@Composable
fun Hint(text: String) = Text(text, color = Texto2, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
fun ShizukuCard(s: UiState, ctx: Context) {
    GlassCard {
        when (s.shizuku) {
            ShizukuState.PRONTO -> {
                CardTitle(Icons.Rounded.VerifiedUser, "Shizuku conectado", Ok)
                Hint(if (s.uid == 0) "Rodando com root." else "Rodando pela depuração sem fio. Tudo certo.")
            }
            ShizukuState.NAO_INSTALADO -> {
                CardTitle(Icons.Rounded.Download, "Instale o Shizuku", Alerta)
                Hint("O Vitral usa o Shizuku para mexer na pasta do app Temas sem root. Ele é grátis e está na Play Store.")
                Button(onClick = { openUrl(ctx, "market://details?id=${Shell.SHIZUKU_PKG}") }) { Text("Baixar o Shizuku") }
            }
            ShizukuState.PARADO -> {
                CardTitle(Icons.Rounded.PowerSettingsNew, "Ligue o Shizuku", Alerta)
                Hint(
                    "1. Conecte no Wi-Fi.\n" +
                        "2. Ative as Opções do desenvolvedor (toque 7 vezes em \"Versão do SO\" em Sobre o telefone).\n" +
                        "3. Abra o Shizuku e toque em \"Parear\" na parte de Depuração sem fio. Siga o aviso para digitar o código de pareamento.\n" +
                        "4. Toque em \"Iniciar\" no Shizuku e volte para cá.\n\n" +
                        "Depois de reiniciar o celular, basta tocar em Iniciar de novo."
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ctx.packageManager.getLaunchIntentForPackage(Shell.SHIZUKU_PKG)?.let { ctx.startActivity(it) }
                    }) { Text("Abrir Shizuku") }
                    OutlinedButton(onClick = { openUrl(ctx, "https://shizuku.rikka.app/guide/setup/") }) { Text("Guia") }
                }
            }
            ShizukuState.SEM_PERMISSAO -> {
                CardTitle(Icons.Rounded.Key, "Falta a permissão", Alerta)
                Hint("O Shizuku está ligado. Toque abaixo e escolha \"Permitir sempre\".")
                Button(onClick = { Shell.requestPermission() }) { Text("Dar permissão") }
            }
            ShizukuState.VERSAO_ANTIGA -> {
                CardTitle(Icons.Rounded.Update, "Atualize o Shizuku", Alerta)
                Hint("Essa versão do Shizuku é antiga demais. Atualize pela Play Store.")
            }
        }
    }
}

@Composable
fun ThemeCard(s: UiState, onPick: () -> Unit) {
    GlassCard {
        CardTitle(Icons.Rounded.Palette, "1. Tema para instalar")
        val p = s.picked
        if (p == null) {
            Hint("Escolha o arquivo .mtz. Você também pode abrir um .mtz direto pelo gerenciador de arquivos com o Vitral.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                p.preview?.let {
                    Image(it, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(84.dp).height(170.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Borda, RoundedCornerShape(14.dp)))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p.info.title ?: p.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    p.info.author?.let { Hint("por $it") }
                    Hint(Formatter.formatShortFileSize(LocalContext.current, p.size))
                    if (p.info.modules.isNotEmpty())
                        Hint("Muda: " + p.info.modules.map(Themes::moduleLabel).distinct().joinToString(", "))
                }
            }
        }
        s.pickError?.let { Text(it, color = Alerta, fontSize = 14.sp) }
        OutlinedButton(onClick = onPick, enabled = !s.busy) {
            Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(8.dp))
            Text(if (p == null) "Escolher arquivo .mtz" else "Trocar arquivo")
        }
    }
}

@Composable
fun TargetCard(s: UiState, vm: MainViewModel, ctx: Context) {
    GlassCard {
        CardTitle(Icons.Rounded.SwapHoriz, "2. Backup que vai ser trocado")
        when {
            s.loading && s.targets.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
            s.targets.isEmpty() -> {
                Hint(
                    "Não achei nenhum backup de tema. Abra o app Temas, faça o backup do tema que você usa agora " +
                        "e volte aqui. O Vitral vai trocar esse backup pelo tema novo (e guarda uma cópia do original)."
                )
                s.listError?.let { Text("Erro: $it", color = Alerta, fontSize = 13.sp) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openThemes(ctx) }) { Text("Abrir Temas") }
                    OutlinedButton(onClick = { vm.refresh() }) { Text("Procurar de novo") }
                }
            }
            else -> {
                Hint("O mais recente já vem marcado. Normalmente é o backup que você acabou de criar.")
                s.targets.forEach { t -> FileRow(t, selected = t.path == s.selected, enabled = !s.busy) { vm.select(t.path) } }
                TextButton(onClick = { vm.refresh() }) { Text("Atualizar lista") }
            }
        }
    }
}

@Composable
fun FileRow(t: ThemeFile, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0x337C9CFF) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(t.name.removeSuffix(".mtz"), color = Color.White, fontSize = 15.sp, maxLines = 2)
            Text(
                DateUtils.getRelativeTimeSpanString(t.modified).toString() + " · " +
                    Formatter.formatShortFileSize(ctx, t.size),
                color = Texto2, fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun InstallButton(s: UiState, vm: MainViewModel) {
    val ready = s.picked != null && s.selected != null && !s.busy
    Button(
        onClick = vm::install, enabled = ready,
        modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
    ) {
        if (s.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        else { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Instalar tema", fontSize = 17.sp) }
    }
    if (!ready && !s.busy) {
        Hint(
            when {
                s.picked == null -> "Escolha um arquivo .mtz para continuar."
                s.selected == null -> "Crie um backup no app Temas para continuar."
                else -> ""
            }
        )
    }
}

@Composable
fun StepsCard(s: UiState) {
    GlassCard {
        s.steps.forEachIndexed { i, text ->
            val last = i == s.steps.lastIndex
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (last && s.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Check, null, tint = Ok, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, color = Texto2, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun RestoreCard(s: UiState, vm: MainViewModel) {
    var confirm by remember { mutableStateOf<ThemeFile?>(null) }
    GlassCard {
        CardTitle(Icons.Rounded.Restore, "Cópias dos backups originais")
        Hint("Toque em uma cópia para devolver o backup original ao app Temas. Elas ficam em Download/Vitral/backups.")
        s.backups.take(8).forEach { b ->
            FileRow(b, selected = false, enabled = !s.busy) { confirm = b }
        }
    }
    confirm?.let { b ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restaurar backup?") },
            text = { Text("O arquivo \"${Themes.originalName(b.name)}\" do app Temas vai voltar a ser o backup original.") },
            confirmButton = { TextButton(onClick = { confirm = null; vm.restore(b) }) { Text("Restaurar") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}
