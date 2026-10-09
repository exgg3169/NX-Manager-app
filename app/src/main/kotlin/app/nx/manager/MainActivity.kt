package app.nx.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nx.patches.youtube.GmsCorePatch
import nx.patches.youtube.YouTube

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF7DA2FF)) else lightColorScheme(primary = Color(0xFF1E5EFF))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private const val MICROG_URL = "https://github.com/MorpheApp/MicroG-RE/releases/latest"

@Composable
private fun Screen() {
    val context = LocalContext.current
    val state by PatchSession.state.collectAsState()

    // Keep the screen on while patching; the work takes about a minute.
    LaunchedEffect(state.running) {
        val window = (context as? ComponentActivity)?.window ?: return@LaunchedEffect
        if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PatchSession.selectApk(context, uri)
    }
    val saveApk = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri -> if (uri != null) PatchSession.save(context, uri) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NX Manager", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "YouTube APK'sını seçin, patch'leri işaretleyin ve oluşturun. Önerilen sürüm: " +
                YouTube.TESTED_VERSIONS.joinToString(),
            style = MaterialTheme.typography.bodyMedium,
        )

        Section("1. APK") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { pickApk.launchApkPicker() }, enabled = !state.running && !state.loading) {
                    Text("APK seç")
                }
                if (state.loading) CircularProgressIndicator(Modifier.size(24.dp))
            }
            state.apk?.let { Text("${it.packageName}  •  ${it.versionName}", fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            if (state.apk != null && state.apk?.versionName !in YouTube.TESTED_VERSIONS) {
                Text("Bu sürüm denenmedi; çalışmayabilir.", color = MaterialTheme.colorScheme.error)
            }
        }

        Section("2. Patch'ler") {
            PatchSession.patches.forEach { patch ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = patch.id in state.selected,
                        onCheckedChange = { PatchSession.toggle(patch.id) },
                        enabled = !state.running,
                    )
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(patch.title, fontWeight = FontWeight.SemiBold)
                        Text(patch.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (GmsCorePatch.id in state.selected) {
                OutlinedTextField(
                    value = state.packageName,
                    onValueChange = PatchSession::setPackageName,
                    label = { Text("Yeni paket adı") },
                    singleLine = true,
                    enabled = !state.running,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { context.openUrl(MICROG_URL) }) { Text("microG'yi indir") }
            }
        }

        Section("3. Oluştur") {
            val ready = state.apk?.packageName == YouTube.PACKAGE && state.selected.isNotEmpty() && !state.running
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { PatchSession.start(context) }, enabled = ready) { Text("Patch'le") }
                if (state.running) CircularProgressIndicator(Modifier.size(24.dp))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.log.isNotEmpty()) LogView(state.log)
        }

        state.result?.let { result ->
            Section("4. Kur") {
                Text("${result.name} hazır (${result.length() / (1024 * 1024)} MB)")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (context.packageManager.canRequestPackageInstalls()) {
                            PatchSession.install(context)
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text("Kur") }
                    OutlinedButton(onClick = { saveApk.launch(result.name) }) { Text("Kaydet") }
                }
                state.installStatus?.let { Text(it) }
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
}

/** The APK picker; YouTube downloads are often typed as generic binaries, so allow those too. */
private fun androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Uri?>.launchApkPicker() =
    launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun LogView(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { listState.animateScrollToItem(lines.lastIndex) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

private fun android.content.Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
    }
}
