package nx.patcher

import java.io.File
import java.util.zip.ZipFile

/** Basic facts about an APK, read without patching it. */
data class ApkInfo(val packageName: String, val versionName: String?)

object Patcher {
    private val dexName = Regex("classes(\\d*)\\.dex")

    fun inspect(apk: File): ApkInfo = ZipFile(apk).use { zip ->
        val manifest = Manifest(zip.read("AndroidManifest.xml"))
        ApkInfo(manifest.packageName, manifest.versionName)
    }

    /**
     * Applies [patches] to [input] and writes a signed APK to [output].
     * [workDir] holds temporary files and is cleaned afterwards.
     */
    fun patch(
        input: File,
        output: File,
        patches: List<Patch>,
        options: PatchOptions,
        key: SigningKey,
        workDir: File,
        log: (String) -> Unit,
    ) {
        workDir.mkdirs()
        val unsigned = File(workDir, "unsigned.apk")
        try {
            // Everything that changes, built first so the parsed dex files can be freed before writing.
            val changes = buildChanges(input, patches, options, log)

            log("APK paketleniyor…")
            val reencoded = ZipRewriter.rewrite(input, unsigned, changes, ::isOldSignature)
            if (reencoded > 0) log("  $reencoded dosya ham kopyalanamadı, yeniden yazıldı")

            log("İmzalanıyor…")
            signApk(unsigned, output, key)
            log("Bitti: ${output.name} (${output.length() / (1024 * 1024)} MB)")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildChanges(
        input: File,
        patches: List<Patch>,
        options: PatchOptions,
        log: (String) -> Unit,
    ): Map<String, ByteArray> {
        val ctx = ZipFile(input).use { zip ->
            val manifest = Manifest(zip.read("AndroidManifest.xml"))
            log("Uygulama: ${manifest.packageName} ${manifest.versionName}")
            for (patch in patches) {
                if (patch.packageName != manifest.packageName) {
                    throw PatchException("\"${patch.title}\" ${patch.packageName} içindir, bu APK ${manifest.packageName}")
                }
                if (manifest.versionName !in patch.testedVersions) {
                    log("Uyarı: \"${patch.title}\" bu sürümde denenmedi (denenen: ${patch.testedVersions.joinToString()})")
                }
            }
            val dexEntries = zip.entries().asSequence()
                .mapNotNull { e -> dexName.matchEntire(e.name)?.let { e.name to (it.groupValues[1].toIntOrNull() ?: 1) } }
                .sortedBy { it.second }
                .map { (name, _) -> name to zip.read(name) }
                .toList()
            log("${dexEntries.size} dex dosyası okunuyor…")
            PatchContext(DexStore(dexEntries), manifest, options, log)
        }

        log("Kod taranıyor…")
        ctx.dex.indexStrings(patches.flatMap { it.filters }.flatMap { it.strings }.toSet())

        for (patch in patches) {
            log("▶ ${patch.title}")
            patch.apply(ctx)
        }

        log("Değişen dex dosyaları yazılıyor…")
        val changes = LinkedHashMap<String, ByteArray>()
        changes["AndroidManifest.xml"] = ctx.manifest.toBytes()
        changes += ctx.dex.writeModified { log("  $it") }
        var nextIndex = ctx.dex.dexes.size + 1
        ctx.extraDex.forEach { changes["classes${nextIndex++}.dex"] = it }
        return changes
    }

    private fun isOldSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return name == "stamp-cert-sha256"
        val file = name.removePrefix("META-INF/")
        return '/' !in file && (file == "MANIFEST.MF" ||
            file.substringAfterLast('.', "").uppercase() in setOf("SF", "RSA", "DSA", "EC"))
    }

    private fun ZipFile.read(name: String): ByteArray =
        getInputStream(getEntry(name) ?: throw PatchException("$name not found in APK")).use { it.readBytes() }
}
