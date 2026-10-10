package nx.patcher

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream

/** Edits the binary AndroidManifest.xml of an APK. */
class Manifest(bytes: ByteArray) {
    private val block = AndroidManifestBlock.load(ByteArrayInputStream(bytes))

    var packageName: String
        get() = block.packageName
        set(value) {
            block.packageName = value
        }

    val versionName: String? get() = block.versionName

    val application: ResXmlElement get() = block.applicationElement

    fun elements(): Sequence<ResXmlElement> = block.recursiveElements().asSequence()

    fun attr(element: ResXmlElement, id: Int): ResXmlAttribute? = element.searchAttributeByResourceId(id)

    fun stringAttr(element: ResXmlElement, id: Int): String? =
        attr(element, id)?.takeIf { it.valueType == ValueType.STRING }?.valueAsString

    /** The resource id an attribute refers to (e.g. `@mipmap/ic_launcher`), or null. */
    fun referenceAttr(element: ResXmlElement, id: Int): Int? =
        attr(element, id)?.takeIf { it.valueType == ValueType.REFERENCE }?.data

    fun setStringAttr(element: ResXmlElement, id: Int, value: String) {
        element.getOrCreateAndroidAttribute(attrNames.getValue(id), id).valueAsString = value
    }

    /** Applies [transform] to every string attribute value; returns how many changed. */
    fun replaceValues(transform: (String) -> String?): Int {
        var changed = 0
        for (element in elements().toList()) {
            for (attribute in element.attributes.asSequence().toList()) {
                if (attribute.valueType != ValueType.STRING) continue
                val old = attribute.valueAsString ?: continue
                val new = transform(old) ?: continue
                if (new != old) {
                    attribute.valueAsString = new
                    changed++
                }
            }
        }
        return changed
    }

    /** Turns `.Foo` component names into `com.package.Foo` so a package rename keeps them valid. */
    fun expandRelativeClassNames() {
        val pkg = packageName
        val classAttrs = listOf(NAME, TARGET_ACTIVITY, BACKUP_AGENT, PARENT_ACTIVITY_NAME)
        val tags = setOf("application", "activity", "activity-alias", "service", "receiver", "provider")
        for (element in elements().filter { it.name in tags }) {
            for (id in classAttrs) {
                val value = stringAttr(element, id) ?: continue
                if (value.startsWith(".")) setStringAttr(element, id, pkg + value)
            }
        }
    }

    /** Drops the markers that make Android refuse to install the base APK without its splits. */
    fun removeSplitRequirements() {
        for (element in listOf(block.manifestElement, application)) {
            element.removeAttributesWithId(IS_SPLIT_REQUIRED)
            element.removeAttributesWithName("requiredSplitTypes")
            element.removeAttributesWithName("splitTypes")
        }
        application.removeElementsIf { e ->
            e.name == "meta-data" && stringAttr(e, NAME)?.startsWith("com.android.vending.splits") == true
        }
    }

    fun addMetaData(name: String, value: String) {
        application.removeElementsIf { it.name == "meta-data" && stringAttr(it, NAME) == name }
        val element = application.newElement("meta-data")
        setStringAttr(element, NAME, name)
        setStringAttr(element, VALUE, value)
    }

    /** Records an enabled NX feature in the meta-data "nx.features", read by the extension. */
    fun addFeature(feature: String) {
        val existing = application.elements.asSequence()
            .firstOrNull { it.name == "meta-data" && stringAttr(it, NAME) == "nx.features" }
            ?.let { stringAttr(it, VALUE) }
            ?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
        if (feature !in existing) addMetaData("nx.features", (existing + feature).joinToString(","))
    }

    /** Declares an activity implemented by our extension code. */
    fun addActivity(className: String) {
        if (elements().any { it.name == "activity" && stringAttr(it, NAME) == className }) return
        val activity = application.newElement("activity")
        setStringAttr(activity, NAME, className)
        activity.getOrCreateAndroidAttribute("exported", EXPORTED).setValueAsBoolean(false)
    }

    /** Sets the label of the application and of every launcher entry. */
    fun setLauncherLabel(label: String) {
        setStringAttr(application, LABEL, label)
        for (element in elements().filter { it.name == "activity" || it.name == "activity-alias" }) {
            val isLauncher = element.recursiveElements().asSequence().any {
                it.name == "category" && stringAttr(it, NAME) == "android.intent.category.LAUNCHER"
            }
            if (isLauncher) setStringAttr(element, LABEL, label)
        }
    }

    /** Makes another app visible to this one on Android 11+ (package visibility). */
    fun addQueriedPackage(name: String) {
        val queries = block.manifestElement.getOrCreateElement("queries")
        val exists = queries.recursiveElements().asSequence().any { it.name == "package" && stringAttr(it, NAME) == name }
        if (!exists) setStringAttr(queries.newElement("package"), NAME, name)
    }

    /** The activity started from the launcher (alias targets are resolved). */
    fun launcherActivity(): String? {
        for (element in elements().filter { it.name == "activity" || it.name == "activity-alias" }) {
            val isLauncher = element.recursiveElements().asSequence().any {
                it.name == "category" && stringAttr(it, NAME) == "android.intent.category.LAUNCHER"
            }
            if (isLauncher) return stringAttr(element, TARGET_ACTIVITY) ?: stringAttr(element, NAME)
        }
        return null
    }

    fun toBytes(): ByteArray {
        block.refreshFull()
        return block.bytes
    }

    companion object {
        const val LABEL = 0x01010001
        const val ICON = 0x01010002
        const val ROUND_ICON = 0x0101052c
        const val NAME = 0x01010003
        const val EXPORTED = 0x01010010
        const val PERMISSION = 0x01010006
        const val AUTHORITIES = 0x01010018
        const val VALUE = 0x01010024
        const val TARGET_ACTIVITY = 0x01010202
        const val BACKUP_AGENT = 0x0101027f
        const val PARENT_ACTIVITY_NAME = 0x010103a7
        const val IS_SPLIT_REQUIRED = 0x01010591

        private val attrNames = mapOf(
            LABEL to "label", NAME to "name", PERMISSION to "permission", AUTHORITIES to "authorities", VALUE to "value",
            TARGET_ACTIVITY to "targetActivity", BACKUP_AGENT to "backupAgent",
            PARENT_ACTIVITY_NAME to "parentActivityName",
        )
    }
}
