package nx.patches.youtube

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction32x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.insertAtTarget
import nx.patcher.invokeStaticRange
import nx.patcher.parameterRegister
import nx.patcher.parseMethodReference
import nx.patcher.returnIndexes
import nx.patcher.parameterRegisterCount
import nx.patcher.single
import nx.patcher.stringValue

/**
 * Lets the extension hide feed components. YouTube converts every server-described element into a
 * UI component in one method; at its start we ask `nx.ext.Litho.isFiltered(identifier, path)` and
 * return an empty component when the answer is yes.
 */
object LithoFilterPatch : Patch {
    override val id = "youtube-litho-filter"
    override val title = "Bileşen filtresi"
    override val description = "Akıştaki bileşenleri gizleyebilmek için gerekli altyapı."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(SettingsPatch)

    private const val HOOK = "Lnx/ext/Litho;->isFiltered(Ljava/lang/String;Ljava/lang/StringBuilder;)Z"

    private val elementConverter = MethodFilter(
        "Element converter",
        strings = listOf("Element missing correct type extension", "Element missing type"),
        returnType = "L",
    )

    // The empty component; its builder is the only static method in the class.
    private val emptyComponent = MethodFilter("Empty component", strings = listOf("EmptyComponent"), name = "<init>")

    override val filters = listOf(elementConverter, emptyComponent)

    override fun apply(ctx: PatchContext) {
        val dex = ctx.dex

        // Empty component: builder(componentContext) -> builder object holding the component.
        val emptyType = dex.single(emptyComponent).classType
        val emptyClass = dex.classDef(emptyType)!!
        val builderMethod = emptyClass.methods.singleOrNull {
            com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.size == 1
        } ?: throw PatchException("Empty component builder not found")
        val builderType = builderMethod.returnType
        val componentField: Field = dex.classDef(builderType)?.fields?.singleOrNull { it.type == emptyType }
            ?: throw PatchException("Empty component field not found")

        val converterHit = dex.single(elementConverter)
        val converter = dex.edit(converterHit)
        val contextType = converter.parameterTypes.getOrNull(1)?.toString()
            ?: throw PatchException("Element converter has unexpected parameters")
        val (identifierField, pathField) = contextFields(ctx, contextType)

        val code = converter.code
        if (code.registerCount - converter.parameterRegisterCount < 2) throw PatchException("No free registers in converter")
        val componentContext = code.parameterRegister(converter, 1)
        val conversionContext = code.parameterRegister(converter, 2)
        val keep = code.newLabelForIndex(0)
        val builderRef = parseMethodReference("${builderMethod.definingClass}->${builderMethod.name}(${builderMethod.parameterTypes.joinToString("")})${builderMethod.returnType}")
        // Inserted before the label, so the original first instruction stays the jump target.
        listOf(
                BuilderInstruction32x(Opcode.MOVE_OBJECT_16, 0, conversionContext),
                BuilderInstruction22c(Opcode.IGET_OBJECT, 1, 0, identifierField),
                BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 0, pathField),
                BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0, parseMethodReference(HOOK)),
                BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                BuilderInstruction21t(Opcode.IF_EQZ, 0, keep),
                BuilderInstruction32x(Opcode.MOVE_OBJECT_16, 0, componentContext),
                BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, builderRef),
                BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 0, componentField),
                BuilderInstruction11x(Opcode.RETURN_OBJECT, 0),
        ).asReversed().forEach { code.addInstruction(0, it) }
        ctx.log("  Bileşen kancası: ${converterHit.classType}->${converterHit.method.name}")
    }

    /** Finds the identifier (from toString) and path builder fields of the conversion context. */
    private fun contextFields(ctx: PatchContext, type: String): Pair<FieldReference, FieldReference> {
        val cls = ctx.dex.classDef(type) ?: throw PatchException("Conversion context $type not found")
        val path = cls.fields.singleOrNull { it.type == "Ljava/lang/StringBuilder;" }
            ?: throw PatchException("Conversion context path not found")
        val toString = cls.methods.firstOrNull { it.name == "toString" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("Conversion context toString not found")
        val ins = toString.implementation!!.instructions.toList()
        val label = ins.indexOfFirst { it.stringValue?.endsWith("identifierProperty=") == true }
        // The value appended right after the label.
        val append = (label + 2 until ins.size).firstOrNull {
            (it.let(ins::get) as? ReferenceInstruction)?.reference.let { r -> r is MethodReference && r.name == "append" }
        } ?: throw PatchException("identifierProperty not found")
        val valueRegister = (ins[append] as FiveRegisterInstruction).registerD
        val load = (append - 1 downTo 0).firstOrNull {
            ins[it].opcode == Opcode.IGET_OBJECT && (ins[it] as OneRegisterInstruction).registerA == valueRegister
        } ?: throw PatchException("identifierProperty field not found")
        val identifier = (ins[load] as ReferenceInstruction).reference as FieldReference
        return identifier to ImmutableFieldReference(path.definingClass, path.name, path.type)
    }
}

/** Hides ad cards, Premium promotions and shopping shelves in feeds. */
object FeedAdsPatch : Patch {
    override val id = "youtube-feed-ads"
    override val title = "Akış reklamlarını gizle"
    override val description = "Ana sayfa, arama ve izleme sayfasındaki reklam kartlarını, Premium tanıtımlarını " +
        "ve alışveriş raflarını gizler (NX Ayarları'ndan açılıp kapatılabilir)."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(LithoFilterPatch)

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("feed_ads")
        ctx.log("  Reklam, Premium ve alışveriş bileşenleri filtreleniyor")
    }
}

/** Hides Shorts: the navigation bar button and the shelves in feeds. */
object HideShortsPatch : Patch {
    override val id = "youtube-hide-shorts"
    override val title = "Shorts'u gizle"
    override val description = "Alt menüdeki Shorts düğmesini ve akıştaki Shorts raflarını gizler " +
        "(NX Ayarları'ndan ayrı ayrı açılıp kapatılabilir)."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(LithoFilterPatch)

    private const val PIVOT_BAR = "Lcom/google/android/libraries/youtube/rendering/ui/pivotbar/PivotBar;"
    private const val HOOK = "Lnx/ext/NavigationBar;->onButtonCreated(Landroid/view/View;)V"

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("shorts")
        val pivotBar = ctx.dex.edit(PIVOT_BAR)
        // Methods that create a navigation button view.
        val creators = pivotBar.methodList.filter {
            it.returnType == "Landroid/view/View;" && it.parameterTypes.isNotEmpty() && it.implementation != null
        }
        if (creators.isEmpty()) throw PatchException("Navigation button creation not found")
        for (method in creators) {
            val code = method.code
            for (index in code.returnIndexes().asReversed()) {
                val register = (code.instructions[index] as OneRegisterInstruction).registerA
                code.insertAtTarget(index, listOf(invokeStaticRange(register, HOOK)))
            }
        }
        ctx.log("  Alt menü: ${creators.size} düğme oluşturma metodu bağlandı")
    }
}
