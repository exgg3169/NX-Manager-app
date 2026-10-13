package nx.patches.youtube

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import nx.patcher.EditableClass
import nx.patcher.EditableMethod
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.insertAtTarget
import nx.patcher.methodRef
import nx.patcher.parameterRegister
import nx.patcher.parseMethodReference
import nx.patcher.returnIndexes
import nx.patcher.single
import nx.patcher.stringValue

/**
 * GIF comments: a Tenor link in a comment or reply is posted as a GIF. YouTube's servers take a
 * Tenor id with new comments, but this app version cannot send one, so the InnerTube request of
 * such a comment is sent as JSON instead of protobuf (see nx.ext.Gif). Also adds NX's GIF picker.
 */
object CommentGifPatch : Patch {
    override val id = "youtube-comment-gif"
    override val title = "GIF yorumları"
    override val description = "Yorumlara Tenor GIF'i eklemeyi sağlar: NX GIF seçicisinden kopyalanan veya " +
        "Tenor'dan alınan bağlantı yorumda GIF olarak gönderilir."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(SettingsPatch)

    private const val EXT = "Lnx/ext/Gif;"
    private const val ACTIVITY = "nx.ext.GifActivity"

    // The InnerTube request: builds the "youtubei/v1/<path>" URL.
    private val requestUrl = MethodFilter(
        "InnerTube request URL", strings = listOf("youtubei/v1", "asig"), returnType = "Ljava/lang/String;", parameters = emptyList(),
    )

    override val filters = listOf(requestUrl)

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("gif")
        ctx.manifest.addActivity(ACTIVITY)

        val urlHit = ctx.dex.single(requestUrl)
        val cls = ctx.dex.edit(urlHit.classType)
        val url = cls.methodLike(urlHit.method)
        // Public headers getter: no parameters, returns a Map, calls the method that sets Content-Type.
        val headerBuilders = cls.methodList.filter { m ->
            m.implementation?.instructions?.any { it.stringValue == "X-GOOG-API-FORMAT-VERSION" } == true
        }.map { it.name }.toSet()
        val headers = cls.methodList.singleOrNull { m ->
            AccessFlags.PUBLIC.isSet(m.accessFlags) && m.parameterTypes.isEmpty() && m.returnType == "Ljava/util/Map;" &&
                m.implementation?.instructions?.any { it.methodRef?.let { r -> r.definingClass == cls.type && r.name in headerBuilders } == true } == true
        } ?: throw PatchException("InnerTube request headers not found")
        val body = cls.methodList.singleOrNull { m ->
            AccessFlags.PUBLIC.isSet(m.accessFlags) && m.parameterTypes.isEmpty() && m.returnType == "[B"
        } ?: throw PatchException("InnerTube request body not found")

        // Bridges to the unhooked values, for the extension.
        addBridge(cls, "nxUrl", url, "Ljava/lang/String;")
        addBridge(cls, "nxBody", body, "[B")

        hookReturn(url, "$EXT->url(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;", replace = true)
        hookReturn(body, "$EXT->body(Ljava/lang/Object;[B)[B", replace = true)
        hookReturn(headers, "$EXT->headers(Ljava/lang/Object;Ljava/util/Map;)V", replace = false)
        ctx.log("  InnerTube isteği: ${cls.type} (adres ${url.name}, başlıklar ${headers.name}, gövde ${body.name})")
    }

    /** Passes `this` and every returned value of [method] through [hook]. */
    private fun hookReturn(method: EditableMethod, hook: String, replace: Boolean) {
        val code = method.code
        val thisRegister = code.parameterRegister(method, 0)
        for (index in code.returnIndexes().asReversed()) {
            val value = (code.instructions[index] as OneRegisterInstruction).registerA
            if (value > 15 || thisRegister > 15) throw PatchException("${method.name}: high registers")
            val call = BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, thisRegister, value, 0, 0, 0, parseMethodReference(hook))
            code.insertAtTarget(index, if (replace) listOf(call, BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, value)) else listOf(call))
        }
    }

    /** Adds `public <returnType> name()` that calls [target] on `this`. */
    private fun addBridge(cls: EditableClass, name: String, target: EditableMethod, returnType: String) {
        val code = MutableMethodImplementation(2) // v0, p0 = v1
        code.addInstruction(BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0,
            parseMethodReference("${cls.type}->${target.name}()$returnType")))
        code.addInstruction(BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0))
        code.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
        cls.addMethod(ImmutableMethod(cls.type, name, emptyList(), returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, code))
    }
}
