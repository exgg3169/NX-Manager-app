package nx.patches.youtube

import com.reandroid.arsc.chunk.xml.ResXmlElement
import nx.patcher.Patch
import nx.patcher.PatchContext

/**
 * Adds the "NX settings" screen, opened from a new entry at the top of YouTube's settings.
 * Other patches whose features can be switched on and off depend on it.
 */
object SettingsPatch : Patch {
    override val id = "youtube-nx-settings"
    override val title = "NX ayarları"
    override val description = "YouTube ayarlarına, özellikleri açıp kapatmak için \"NX Ayarları\" bölümünü ekler."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS

    const val ACTIVITY = "nx.ext.SettingsActivity"
    const val EXTENSION = "/nx/extension.bin"

    // Classic and newer ("cairo") layouts of the settings list.
    override val xmlResources = listOf("xml/settings_fragment", "xml/settings_fragment_cairo")

    private const val PERSISTENT = 0x0101000d
    private const val TITLE = 0x010101e1
    private const val KEY = 0x010101e8
    private const val SUMMARY = 0x010101e9
    private const val TARGET_PACKAGE = 0x01010021
    private const val TARGET_CLASS = 0x0101002f

    override fun apply(ctx: PatchContext) {
        ctx.addExtension(EXTENSION)
        ctx.manifest.addActivity(ACTIVITY)
        // Runs after the package rename, so the entry points at the installed package name.
        val pkg = ctx.manifest.packageName
        for (name in xmlResources) {
            val doc = ctx.xml(name)
            val root = doc.documentElement
            // The newer layout groups entries in categories; join the first one.
            val parent = root.elements.asSequence().firstOrNull { it.name == "PreferenceCategory" } ?: root
            val pref = parent.newElementAt(0, "Preference")
            // Attributes in increasing id order, as the framework expects.
            pref.getOrCreateAndroidAttribute("persistent", PERSISTENT).setValueAsBoolean(false)
            pref.setString("title", TITLE, "NX Ayarları")
            pref.setString("key", KEY, "nx_settings")
            pref.setString("summary", SUMMARY, "Reklam, Shorts, SponsorBlock, kalite…")
            val intent = pref.newElement("intent")
            intent.setString("targetPackage", TARGET_PACKAGE, pkg)
            intent.setString("targetClass", TARGET_CLASS, ACTIVITY)
        }
        ctx.log("  YouTube ayarlarına \"NX Ayarları\" eklendi")
    }

    private fun ResXmlElement.setString(name: String, id: Int, value: String) {
        getOrCreateAndroidAttribute(name, id).valueAsString = value
    }
}

/** Shows the patched app under its own name and the NX icon on the home screen. */
object AppNamePatch : Patch {
    override val id = "youtube-app-name"
    override val title = "Uygulama adı ve simgesi"
    override val description = "Ana ekranda uygulamayı kendi adıyla (varsayılan: NX YouTube) ve NX simgesiyle gösterir."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val usesLauncherIcon = true

    override fun apply(ctx: PatchContext) {
        ctx.manifest.setLauncherLabel(ctx.options.appName)
        ctx.log("  Ad: ${ctx.options.appName}")

        val icon = ctx.launcherIcon ?: return ctx.log("  Uyarı: simge bulunamadı, YouTube simgesi kaldı")
        for ((layers, prefix) in listOf(icon.foreground to "fg", icon.background to "bg")) {
            for ((density, path) in layers) {
                // Unknown densities get the closest larger image.
                val file = densityFallback(density).first { javaClass.getResource("/nx/icon/${prefix}_$it.png") != null }
                ctx.replaceFile(path, ctx.bundled("/nx/icon/${prefix}_$file.png"))
            }
        }
        // Android 13+ themed icons would still show the YouTube shape.
        for (name in icon.xmlNames) ctx.xml(name).documentElement.removeElementsIf { it.name == "monochrome" }
        ctx.log("  Simge: ${icon.foreground.size + icon.background.size} katman resmi değiştirildi")
    }

    private fun densityFallback(density: String): List<String> =
        listOf(density, "xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")
}
