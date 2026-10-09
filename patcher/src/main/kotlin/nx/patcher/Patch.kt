package nx.patcher

/** Options the user can change before patching. */
data class PatchOptions(
    /** Package name of the patched app, so it can live next to the original. */
    val packageName: String = "app.nx.android.youtube",
)

class PatchContext(
    val dex: DexStore,
    val manifest: Manifest,
    val options: PatchOptions,
    private val logger: (String) -> Unit,
) {
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

    /** Method filters used by the patch, indexed together up front for speed. */
    val filters: List<MethodFilter> get() = emptyList()

    fun apply(ctx: PatchContext)
}
