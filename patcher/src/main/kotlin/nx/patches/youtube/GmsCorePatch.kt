package nx.patches.youtube

import com.android.tools.smali.dexlib2.Opcode
import nx.patcher.Manifest
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.find
import nx.patcher.insertAt
import nx.patcher.invokeStaticRange
import nx.patcher.methodRef
import nx.patcher.parameterRegister
import nx.patcher.replaceConstString
import nx.patcher.replaceWithConst4
import nx.patcher.returnEarly
import nx.patcher.single
import nx.patcher.stringValue

/**
 * Lets YouTube run without root next to the original app by installing it under a new package
 * name and talking to microG (GmsCore) instead of Google Play services.
 *
 * Without root the original signature cannot be kept, and Google Play services refuses apps
 * with the wrong signature. microG accepts them: the app declares, in its manifest, which
 * package and signature microG should present to Google on its behalf.
 */
object GmsCorePatch : Patch {
    override val id = "youtube-gmscore"
    override val title = "GmsCore (microG) desteği"
    override val description = "Root olmadan çalışması için paket adını değiştirir ve Google Play " +
        "Hizmetleri yerine microG kullanır. microG'nin kurulu olması gerekir."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS

    /** Package prefix of the microG build (ReVanced GmsCore / MicroG-RE). */
    const val VENDOR = "app.revanced"
    const val GMS_PACKAGE = "$VENDOR.android.gms"
    private const val EXTENSION = "Lnx/ext/GmsCheck;->check(Landroid/app/Activity;)V"

    // GooglePlayServicesUtil.isGooglePlayServicesAvailable(Context, int): it verifies the
    // signature of the GMS package, which microG cannot pass.
    private val playServicesAvailable = MethodFilter(
        "Play services availability check",
        strings = listOf("MetadataValueReader", "This should never happen."),
        returnType = "I", parameters = listOf("Landroid/content/Context;", "I"), isStatic = true,
    )

    // Throws when the check above fails.
    private val ensurePlayServices = MethodFilter(
        "Play services availability enforcer",
        strings = listOf("Google Play Services not available"),
        returnType = "V", parameters = listOf("Landroid/content/Context;", "I"),
    )

    // Cast needs a Google Play services module that microG does not have.
    private val castContextFetch = MethodFilter(
        "Cast context fetch",
        strings = listOf("Error fetching CastContext."),
        returnType = "V",
    )

    // Allow-list of Google apps for the performance library; the renamed app must stay on it.
    private val googleAppAllowList = MethodFilter(
        "Google app allow-list",
        strings = listOf("com.android.vending", "com.google.android.GoogleCamera"),
        returnType = "Z",
    )

    // Notification registration sends the app's package name; the server expects the original.
    private val notificationRegistration = MethodFilter(
        "Notification registration",
        strings = listOf("Failed to get android ID.", "Exception reading GServices key."),
    )

    // A device compliance check through GmsCore that puts the app in the background when it fails.
    private val deviceComplianceCheck = MethodFilter(
        "Device compliance check",
        strings = listOf("failsafe_enable_gms_device_compliance_check"),
        returnType = "V",
    )

    override val filters = listOf(
        playServicesAvailable, ensurePlayServices, castContextFetch,
        googleAppAllowList, notificationRegistration, deviceComplianceCheck,
    )

    override fun apply(ctx: PatchContext) {
        val manifest = ctx.manifest
        val original = manifest.packageName
        val renamed = ctx.options.packageName
        if (renamed == original) throw PatchException("Yeni paket adı orijinalden farklı olmalı")

        // region Manifest

        manifest.expandRelativeClassNames()
        val launcher = manifest.launcherActivity()
        manifest.packageName = renamed
        manifest.removeSplitRequirements()

        // Exact strings to rename, both in the manifest and in the code.
        val names = HashMap<String, String>()

        // Permissions and content providers of the app itself would clash with the original app.
        manifest.elements().filter { it.name == "permission" }
            .mapNotNull { manifest.stringAttr(it, Manifest.NAME) }
            .filter { it.startsWith("$original.") }
            .forEach { names[it] = renamed + it.removePrefix(original) }
        val appAuthorities = manifest.elements().filter { it.name == "provider" }
            .mapNotNull { manifest.stringAttr(it, Manifest.AUTHORITIES) }
            .flatMap { it.split(';') }
            .filter { it.startsWith("$original.") }
            .toList()
        appAuthorities.forEach { names[it] = renamed + it.removePrefix(original) }

        // Intents, permissions and providers of Google Play services, served by microG under its own names.
        val gms = GmsNames.load()
        (gms.permissions + gms.actions + gms.authorities).forEach { names[it] = toVendor(it) }
        names["com.google.android.gms"] = GMS_PACKAGE

        val manifestChanges = manifest.replaceValues { value ->
            if (';' in value) value.split(';').joinToString(";") { names[it] ?: it } else names[value]
        }
        manifest.addQueriedPackage(GMS_PACKAGE)
        manifest.addMetaData("$GMS_PACKAGE.SPOOFED_PACKAGE_NAME", original)
        manifest.addMetaData("$GMS_PACKAGE.SPOOFED_PACKAGE_SIGNATURE", YouTube.SIGNATURE_SHA1)
        ctx.log("  Manifest: paket $original → $renamed, $manifestChanges değer değişti")

        // endregion

        // region Strings in code

        val codeNames = names + ("com.google" to VENDOR) // Google account type.
        val contentUris = (gms.authorities.associateWith(::toVendor) + appAuthorities.associateWith { names.getValue(it) })
            .map { (from, to) -> "content://$from" to "content://$to" }
        val stringChanges = ctx.dex.replaceStrings { s ->
            codeNames[s] ?: contentUris.firstOrNull { (from, _) ->
                s == from || s.startsWith("$from/")
            }?.let { (from, to) -> to + s.removePrefix(from) }
        }
        ctx.log("  Kod: $stringChanges metin değişti")

        // endregion

        // region Specific methods

        ctx.dex.edit(ctx.dex.single(playServicesAvailable)).returnEarly(0) // ConnectionResult.SUCCESS
        ctx.dex.find(ensurePlayServices).forEach { ctx.dex.edit(it).returnEarly() }
        optional(ctx, castContextFetch) { it.returnEarly() }

        ctx.dex.edit(ctx.dex.single(googleAppAllowList)).code.let { code ->
            code.instructions.withIndex().filter { it.value.stringValue == original }
                .forEach { (i, _) -> code.replaceConstString(i, renamed) }
        }

        ctx.dex.edit(ctx.dex.single(notificationRegistration)).code.let { code ->
            val ins = code.instructions
            val call = ins.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL && it.methodRef?.name == "getPackageName" &&
                    it.methodRef?.definingClass == "Landroid/content/Context;"
            }
            if (call < 0 || ins.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw PatchException("Notification registration: getPackageName() not found")
            }
            code.replaceConstString(call + 1, original)
        }

        optional(ctx, deviceComplianceCheck) { method ->
            val code = method.code
            val ins = code.instructions
            val flag = ins.indexOfFirst { it.stringValue == "failsafe_enable_gms_device_compliance_check" }
            val result = (flag + 1 until ins.size).firstOrNull { ins[it].opcode == Opcode.MOVE_RESULT }
                ?: throw PatchException("Device compliance check: flag result not found")
            // Behave as if the check is turned off.
            code.replaceWithConst4(result, 0)
        }

        // Warn the user when microG is missing.
        val activity = launcher ?: throw PatchException("Launcher activity not found")
        val activityType = "L${activity.replace('.', '/')};"
        val onCreate = ctx.dex.edit(activityType).methodList.firstOrNull {
            it.name == "onCreate" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;")
        } ?: throw PatchException("$activityType.onCreate not found")
        onCreate.code.let { code ->
            code.insertAt(0, listOf(invokeStaticRange(code.parameterRegister(onCreate, 0), EXTENSION)))
        }
        ctx.addExtension("/nx/extension.bin")

        // endregion
    }

    private fun toVendor(name: String) = VENDOR + name.removePrefix("com.google")

    private fun optional(ctx: PatchContext, filter: MethodFilter, block: (nx.patcher.EditableMethod) -> Unit) {
        val hits = ctx.dex.find(filter)
        if (hits.size != 1) {
            ctx.log("  Uyarı: ${filter.label} bulunamadı, atlandı")
            return
        }
        block(ctx.dex.edit(hits.single()))
    }
}

/** Names microG serves under its own package prefix, extracted from its manifest. */
class GmsNames(val permissions: Set<String>, val actions: Set<String>, val authorities: Set<String>) {
    companion object {
        fun load(): GmsNames {
            val lines = GmsNames::class.java.getResourceAsStream("/nx/gms-names.txt")!!
                .bufferedReader().readLines().filter { it.isNotBlank() }
            fun of(kind: String) = lines.filter { it.startsWith("$kind ") }.map { it.substringAfter(' ') }.toSet()
            return GmsNames(of("permission"), of("action"), of("authority"))
        }
    }
}
