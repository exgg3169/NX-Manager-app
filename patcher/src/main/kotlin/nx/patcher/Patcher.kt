package nx.patcher

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.TableBlock
import java.io.File
import java.security.MessageDigest
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
     * Checks that [apk] is complete and unmodified by verifying its signature over every byte.
     * Returns a problem description, or null when it is intact and signed by one of [trustedCerts]
     * (SHA-256 of the signing certificate; empty accepts any signer).
     */
    fun verify(apk: File, trustedCerts: Set<String> = emptySet()): String? {
        val result = try {
            ApkVerifier.Builder(apk).build().verify()
        } catch (e: Exception) {
            return "APK okunamadı, dosya bozuk olabilir (${e.message})"
        }
        if (!result.isVerified) {
            val reason = result.allErrors.firstOrNull()?.toString().orEmpty()
            return "APK bozuk veya değiştirilmiş; yeniden indirin. $reason".trim()
        }
        if (trustedCerts.isNotEmpty()) {
            val digests = result.signerCertificates.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
            }
            if (digests.none { it in trustedCerts }) return "APK geçerli ama orijinal imzayla imzalanmamış"
        }
        return null
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
        val patches = withDependencies(patches)
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

    /** Adds missing dependencies, each placed before the first patch that needs it. */
    fun withDependencies(selected: List<Patch>): List<Patch> {
        val result = ArrayList<Patch>()
        fun add(patch: Patch) {
            if (patch in result) return
            patch.dependencies.forEach(::add)
            result += patch
        }
        selected.forEach(::add)
        return result
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
            val (resourceIds, xmlPaths) = resolveResources(
                zip, manifest.packageName,
                patches.flatMap { it.resources }.toSet(), patches.flatMap { it.xmlResources }.toSet(),
            )
            val xmlFiles = xmlPaths.mapValues { (_, path) -> path to zip.read(path) }.toMutableMap()
            val launcherIcon = if (patches.any { it.usesLauncherIcon }) {
                resolveLauncherIcon(zip, manifest, xmlFiles, log)
            } else null
            val dexEntries = zip.entries().asSequence()
                .mapNotNull { e -> dexName.matchEntire(e.name)?.let { e.name to (it.groupValues[1].toIntOrNull() ?: 1) } }
                .sortedBy { it.second }
                .map { (name, _) -> name to zip.read(name) }
                .toList()
            log("${dexEntries.size} dex dosyası okunuyor…")
            PatchContext(DexStore(dexEntries), manifest, options, resourceIds, xmlFiles, launcherIcon, input, log)
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
        changes += ctx.xmlChanges()
        changes += ctx.dex.writeModified { log("  $it") }
        var nextIndex = ctx.dex.dexes.size + 1
        ctx.extraDex.forEach { changes["classes${nextIndex++}.dex"] = it }
        ctx.dex.overflow.forEach { changes["classes${nextIndex++}.dex"] = it }
        return changes
    }

    /**
     * Looks up resource ids and XML file paths by `type/name`. The large resource table is
     * dropped right after.
     */
    private fun resolveResources(
        zip: ZipFile,
        packageName: String,
        ids: Set<String>,
        files: Set<String>,
    ): Pair<Map<String, Int>, Map<String, String>> {
        if (ids.isEmpty() && files.isEmpty()) return emptyMap<String, Int>() to emptyMap()
        val table = zip.getInputStream(zip.getEntry("resources.arsc") ?: throw PatchException("resources.arsc not found"))
            .use { TableBlock.load(it) }
        fun entry(name: String) = table.getResource(packageName, name.substringBefore('/'), name.substringAfter('/'))
        val idMap = ids.mapNotNull { name -> entry(name)?.let { name to it.resourceId } }.toMap()
        val fileMap = files.mapNotNull { name ->
            entry(name)?.stringValues?.asSequence()?.firstOrNull { it.startsWith("res/") }?.let { name to it }
        }.toMap()
        return idMap to fileMap
    }

    /**
     * Follows the manifest's icon and round icon to their adaptive icon XML files and the
     * foreground and background images per density. Adds the XML files to [xmlFiles].
     */
    private fun resolveLauncherIcon(
        zip: ZipFile,
        manifest: Manifest,
        xmlFiles: MutableMap<String, Pair<String, ByteArray>>,
        log: (String) -> Unit,
    ): LauncherIcon? {
        val ids = listOf(Manifest.ICON, Manifest.ROUND_ICON).mapNotNull { manifest.referenceAttr(manifest.application, it) }
        if (ids.isEmpty()) return null
        val table = zip.getInputStream(zip.getEntry("resources.arsc")).use { TableBlock.load(it) }
        fun files(id: Int): Map<String, String> {
            val entry = table.getResource(id) ?: return emptyMap()
            return entry.mapNotNull { e ->
                val path = e.resValue?.valueAsString?.takeIf { it.startsWith("res/") } ?: return@mapNotNull null
                val qualifiers = e.resConfig?.qualifiers.orEmpty()
                val density = DENSITIES.firstOrNull { qualifiers.split('-').contains(it) } ?: "anydpi"
                density to path
            }.toMap()
        }
        val xmlNames = mutableListOf<String>()
        var foreground = emptyMap<String, String>()
        var background = emptyMap<String, String>()
        for (id in ids) {
            for (path in files(id).values.filter { it.endsWith(".xml") }) {
                val name = "launcher-icon:$path"
                val bytes = zip.read(path)
                xmlFiles[name] = path to bytes
                xmlNames += name
                val doc = com.reandroid.arsc.chunk.xml.ResXmlDocument().apply { readBytes(java.io.ByteArrayInputStream(bytes)) }
                for (layer in doc.documentElement.elements.asSequence()) {
                    val ref = layer.searchAttributeByResourceId(DRAWABLE)?.takeIf {
                        it.valueType == com.reandroid.arsc.value.ValueType.REFERENCE
                    }?.data ?: continue
                    val layerFiles = files(ref).filterValues { it.endsWith(".png") || it.endsWith(".webp") }
                    when (layer.name) {
                        "foreground" -> foreground = layerFiles
                        "background" -> background = layerFiles
                    }
                }
            }
        }
        if (xmlNames.isEmpty() || foreground.isEmpty() || background.isEmpty()) {
            log("Uyarı: simge katmanları resim değil, simge değiştirilemeyecek")
            return null
        }
        return LauncherIcon(xmlNames, foreground, background)
    }

    private val DENSITIES = listOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
    private const val DRAWABLE = 0x01010199

    private fun isOldSignature(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return name == "stamp-cert-sha256"
        val file = name.removePrefix("META-INF/")
        return '/' !in file && (file == "MANIFEST.MF" ||
            file.substringAfterLast('.', "").uppercase() in setOf("SF", "RSA", "DSA", "EC"))
    }

    private fun ZipFile.read(name: String): ByteArray =
        getInputStream(getEntry(name) ?: throw PatchException("$name not found in APK")).use { it.readBytes() }
}
