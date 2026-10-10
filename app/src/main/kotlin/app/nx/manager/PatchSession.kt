package app.nx.manager

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nx.patcher.ApkInfo
import nx.patcher.Patch
import nx.patcher.PatchOptions
import nx.patcher.Patcher
import nx.patcher.SigningKey
import nx.patches.youtube.YouTube
import java.io.File

/** Everything the screen shows; survives configuration changes because it lives in [PatchSession]. */
data class UiState(
    val apk: ApkInfo? = null,
    val loading: Boolean = false,
    /** Why the selected APK cannot be patched (wrong app, broken download…), or null. */
    val apkProblem: String? = null,
    val selected: Set<String> = YouTube.patches.filter { it.enabledByDefault }.map { it.id }.toSet(),
    val packageName: String = PatchOptions().packageName,
    val appName: String = PatchOptions().appName,
    val running: Boolean = false,
    val log: List<String> = emptyList(),
    val result: File? = null,
    val error: String? = null,
    val installStatus: String? = null,
)

/** Holds the patching work outside the activity so rotating the screen does not stop it. */
object PatchSession {
    val patches: List<Patch> = YouTube.patches

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private fun inputFile(context: Context) = File(context.cacheDir, "input.apk")
    private fun outputFile(context: Context) = File(context.filesDir, "patched/youtube-nx.apk")

    fun selectApk(context: Context, uri: Uri) {
        _state.update { UiState(loading = true, selected = it.selected, packageName = it.packageName, appName = it.appName) }
        scope.launch {
            try {
                val file = inputFile(context)
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
                val info = Patcher.inspect(file)
                val problem = if (info.packageName != YouTube.PACKAGE) {
                    "Bu bir YouTube APK'sı değil (${info.packageName})."
                } else {
                    // A broken download only fails late while repackaging, so check every byte now.
                    Patcher.verify(file, YouTube.SIGNING_CERTS)
                }
                _state.update { it.copy(apk = info, loading = false, apkProblem = problem) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, apkProblem = "APK okunamadı, dosya bozuk olabilir: ${e.message}") }
            }
        }
    }

    fun toggle(id: String) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun setPackageName(name: String) = _state.update { it.copy(packageName = name.trim()) }

    fun setAppName(name: String) = _state.update { it.copy(appName = name) }

    fun start(context: Context) {
        val current = _state.value
        if (current.running || current.apk == null) return
        val chosen = patches.filter { it.id in current.selected }
        _state.update { it.copy(running = true, log = emptyList(), result = null, error = null, installStatus = null) }
        val appContext = context.applicationContext
        // Large methods can need a deep stack while writing dex files.
        Thread(null, {
            val output = outputFile(appContext).apply { parentFile?.mkdirs(); delete() }
            try {
                Patcher.patch(
                    input = inputFile(appContext),
                    output = output,
                    patches = chosen,
                    options = PatchOptions(packageName = current.packageName, appName = current.appName.trim().ifEmpty { "NX YouTube" }),
                    key = SigningKey.loadOrCreate(File(appContext.filesDir, "keys")),
                    workDir = File(appContext.cacheDir, "work"),
                    log = ::log,
                )
                _state.update { it.copy(running = false, result = output) }
            } catch (t: Throwable) {
                log("HATA: ${t.message ?: t.javaClass.simpleName}")
                _state.update { it.copy(running = false, error = t.message ?: t.toString()) }
            }
        }, "patcher", 64L shl 20).start()
    }

    fun log(line: String) = _state.update { it.copy(log = it.log + line) }

    fun setInstallStatus(message: String) = _state.update { it.copy(installStatus = message) }

    fun save(context: Context, uri: Uri) {
        val file = _state.value.result ?: return
        scope.launch {
            try {
                context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                setInstallStatus("Kaydedildi")
            } catch (e: Exception) {
                setInstallStatus("Kaydedilemedi: ${e.message}")
            }
        }
    }

    fun install(context: Context) {
        val file = _state.value.result ?: return
        setInstallStatus("Kuruluyor…")
        scope.launch {
            try {
                Installer.install(context.applicationContext, file)
            } catch (e: Exception) {
                setInstallStatus("Kurulum başlatılamadı: ${e.message}")
            }
        }
    }
}
