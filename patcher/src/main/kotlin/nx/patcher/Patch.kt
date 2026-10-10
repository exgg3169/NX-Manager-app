package nx.patcher

import com.reandroid.arsc.chunk.xml.ResXmlDocument
import java.io.ByteArrayInputStream

/** Options the user can change before patching. */
data class PatchOptions(
    /** Package name of the patched app, so it can live next to the original. */
    val packageName: String = "app.nx.android.youtube",
    /** Name shown under the launcher icon. */
    val appName: String = "NX YouTube",
)

class PatchContext(
    val dex: DexStore,
    val manifest: Manifest,
    val options: PatchOptions,
    private val resourceIds: Map<String, Int>,
    private val xmlFiles: Map<String, Pair<String, ByteArray>>,
    /** The launcher icon's files, when a patch set [Patch.usesLauncherIcon]. */
    val launcherIcon: LauncherIcon?,
    private val apk: java.io.File,
    private val logger: (String) -> Unit,
) {
    /** Reads a file of the input APK (the latest replacement if a patch changed it). */
    fun readApkFile(path: String): ByteArray = fileChanges[path] ?: java.util.zip.ZipFile(apk).use { zip ->
        zip.getInputStream(zip.getEntry(path) ?: throw PatchException("$path not found in APK")).use { it.readBytes() }
    }

    private val fileChanges = LinkedHashMap<String, ByteArray>()

    /** Replaces a file of the APK, e.g. a resource image. */
    fun replaceFile(path: String, bytes: ByteArray) {
        fileChanges[path] = bytes
    }

    /** Reads a resource shipped inside the patcher. */
    fun bundled(resource: String): ByteArray = javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
        ?: throw PatchException("Missing bundled file $resource")

    private val openXml = LinkedHashMap<String, ResXmlDocument>()

    /** A binary XML resource declared in [Patch.xmlResources], e.g. `xml("xml/settings_fragment")`. */
    fun xml(name: String): ResXmlDocument {
        val (path, bytes) = xmlFiles[name] ?: throw PatchException("XML resource $name not found")
        return openXml.getOrPut(path) { ResXmlDocument().apply { readBytes(ByteArrayInputStream(bytes)) } }
    }

    /** Modified XML resources by path in the APK. */
    internal fun xmlChanges(): Map<String, ByteArray> = fileChanges + openXml.mapValues { (_, doc) ->
        doc.refreshFull()
        doc.bytes
    }

    /** Id of a resource declared in [Patch.resources], e.g. `resourceId("string/app_name")`. */
    fun resourceId(name: String): Long =
        resourceIds[name]?.toLong() ?: throw PatchException("Resource $name not found in the APK")

    /** Extra dex files (our own Java code) appended to the APK. */
    val extraDex = mutableListOf<ByteArray>()

    fun log(message: String) = logger(message)

    /** Loads a precompiled dex shipped inside the patcher. */
    fun addExtension(resource: String) {
        val bytes = javaClass.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw PatchException("Missing extension $resource")
        if (extraDex.none { it.contentEquals(bytes) }) extraDex += bytes
    }
}

interface Patch {
    val id: String
    val title: String
    val description: String
    val enabledByDefault: Boolean get() = true

    /** Package the patch targets. */
    val packageName: String

    /** App versions the patch was verified on (others may still work). */
    val testedVersions: Set<String>

    /** Patches that must run before this one; they are added automatically. */
    val dependencies: List<Patch> get() = emptyList()

    /** Resources the patch looks up by name (`type/name`), resolved before the code is loaded. */
    val resources: List<String> get() = emptyList()

    /** Set when the patch replaces the launcher icon; its files are then found from the manifest. */
    val usesLauncherIcon: Boolean get() = false

    /** XML resource files the patch edits (`type/name`, e.g. `xml/settings_fragment`). */
    val xmlResources: List<String> get() = emptyList()

    /** Method filters used by the patch, indexed together up front for speed. */
    val filters: List<MethodFilter> get() = emptyList()

    fun apply(ctx: PatchContext)
}

/**
 * Files of the app's adaptive launcher icon. [xmlNames] are keys for [PatchContext.xml];
 * [foreground] and [background] map a screen density (e.g. "xxhdpi") to the layer image in the APK.
 */
class LauncherIcon(
    val xmlNames: List<String>,
    val foreground: Map<String, String>,
    val background: Map<String, String>,
)
