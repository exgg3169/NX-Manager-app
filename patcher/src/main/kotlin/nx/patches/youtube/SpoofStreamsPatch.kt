package nx.patches.youtube

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction32x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.ProtoInfo
import nx.patcher.find
import nx.patcher.insertAt
import nx.patcher.insertAtTarget
import nx.patcher.parameterRegisterCount
import nx.patcher.returnIndexes
import nx.patcher.parameterRegister
import nx.patcher.parseMethodReference
import nx.patcher.single

/**
 * Video playback fix: replaces the stream data of each player response with the one YouTube's
 * VR app gets, which plays from plain URLs instead of the attested SABR session that the server
 * rejects on some devices.
 */
object SpoofStreamsPatch : Patch {
    override val id = "youtube-spoof-streams"
    override val title = "Video akışı düzeltmesi"
    override val description = "Videoların bir süre sonra durmasını (ump.spsrejectfailure) önlemek için video " +
        "akışını YouTube VR uygulamasının aldığı akışla değiştirir. NX Ayarları'ndan kapatılabilir."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(SettingsPatch)

    private const val STREAMING_DATA = "Lcom/google/protos/youtube/api/innertube/StreamingDataOuterClass\$StreamingData;"
    private const val HOOK = "Lnx/ext/StreamSpoof;->streamingData(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

    // Player response protobuf field numbers.
    private const val RESPONSE_STREAMING_DATA = 4
    private const val RESPONSE_VIDEO_DETAILS = 11
    private const val VIDEO_DETAILS_VIDEO_ID = 1

    private fun FieldReference.isStreamingData() = type == STREAMING_DATA

    // The player response model copies the stream data out of the response in its constructor.
    private val responseModel = MethodFilter(
        "Player response model", name = "<init>", parameters = listOf("L"), opcodes = listOf(Opcode.IGET_OBJECT),
        extra = { m ->
            m.implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                it.opcode == Opcode.IGET_OBJECT && ref != null && ref.isStreamingData() && ref.definingClass == m.parameterTypes[0].toString()
            }
        },
    )

    private const val PLATYPUS = "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlatypusEnablingManager;"
    private const val PLATYPUS_DECISION =
        "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlatypusEnablingManager\$DecisionWithReason;"
    private const val PLATYPUS_HOOK = "Lnx/ext/StreamSpoof;->disablePlatypus()Z"
    private const val MEDIA_REQUEST_HOOK = "Lnx/ext/StreamSpoof;->mediaRequestBody(Landroid/net/Uri;I[B)[B"
    private const val FLAG_HOOK = "Lnx/ext/StreamSpoof;->flag(Z)Z"

    /**
     * Feature flags that break playback of replaced streams: media fetch config replacement
     * (native), a new playback start path (videos load forever), media session and Shorts
     * watch responses.
     */
    private val conflictingFlags = listOf(45645570L, 45665455L, 45640404L, 45638126L)

    // A media (videoplayback) request: URI, HTTP method and body.
    private val mediaRequest = MethodFilter(
        "Media request", name = "<init>", opcodes = listOf(Opcode.IPUT_OBJECT),
        extra = { m ->
            val types = m.parameterTypes.map { it.toString() }
            types.firstOrNull() == "Landroid/net/Uri;" && "[B" in types && mediaRequestFields(m) != null
        },
    )

    private val flagMethods = conflictingFlags.map { MethodFilter("Flag $it", literals = listOf(it)) }

    override val filters = listOf(responseModel, mediaRequest) + flagMethods

    /** The URI, method and body fields a media request constructor stores. */
    private fun mediaRequestFields(m: com.android.tools.smali.dexlib2.iface.Method): Triple<FieldReference, FieldReference, FieldReference>? {
        val stores = m.implementation?.instructions?.mapNotNull {
            val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
            if (ref != null && ref.definingClass == m.definingClass && (it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.IPUT)) ref else null
        } ?: return null
        val uri = stores.firstOrNull { it.type == "Landroid/net/Uri;" } ?: return null
        val method = stores.firstOrNull { it.type == "I" } ?: return null
        val body = stores.firstOrNull { it.type == "[B" } ?: return null
        return Triple(uri, method, body)
    }

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("spoof_streams")
        val hit = ctx.dex.single(responseModel)
        val responseType = hit.method.parameterTypes[0].toString()
        val responseClass = ctx.dex.classDef(responseType) ?: throw PatchException("Player response $responseType not found")

        val streamingField = ProtoInfo.field(responseClass, RESPONSE_STREAMING_DATA)
        if (!streamingField.isStreamingData()) throw PatchException("Unexpected player response layout")
        val detailsField = ProtoInfo.field(responseClass, RESPONSE_VIDEO_DETAILS)
        val detailsClass = ctx.dex.classDef(detailsField.type) ?: throw PatchException("Video details class not found")
        val videoIdField = ProtoInfo.field(detailsClass, VIDEO_DETAILS_VIDEO_ID)
        if (videoIdField.type != "Ljava/lang/String;") throw PatchException("Unexpected video details layout")

        addVideoIdMethod(ctx, responseType, detailsField, videoIdField)

        val model = ctx.dex.edit(hit)
        val code = model.code
        val responseRegister = code.parameterRegister(model, 1)
        val reads = code.instructions.indices.filter {
            val ins = code.instructions[it]
            ins.opcode == Opcode.IGET_OBJECT && ((ins as ReferenceInstruction).reference as FieldReference).isStreamingData()
        }
        for (index in reads.asReversed()) {
            val register = (code.instructions[index] as TwoRegisterInstruction).registerA
            if (register > 15 || responseRegister > 15) throw PatchException("Player response model uses high registers")
            code.insertAt(
                index + 1,
                listOf(
                    BuilderInstruction35c(Opcode.INVOKE_STATIC, 2, register, responseRegister, 0, 0, 0, parseMethodReference(HOOK)),
                    BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register),
                    BuilderInstruction21c(Opcode.CHECK_CAST, register, ImmutableTypeReference(STREAMING_DATA)),
                ),
            )
        }
        ctx.log("  Oynatıcı yanıtı: $responseType (akış ${streamingField.name}, video kimliği ${detailsField.name}.${videoIdField.name})")
        ctx.log("  Kanca: ${hit.classType}-><init> (${reads.size} yer)")

        disablePlatypus(ctx)
        hookMediaRequest(ctx)
        var flags = 0
        for (filter in flagMethods) {
            val literal = filter.literals.single()
            for (found in ctx.dex.find(filter)) flags += overrideFlag(ctx.dex.edit(found).code, literal)
        }
        ctx.log("  Çakışan özellik bayrakları: $flags yer")
    }

    /** The native player (Platypus) streams only over SABR; use the regular player instead. */
    private fun disablePlatypus(ctx: PatchContext) {
        val cls = ctx.dex.edit(PLATYPUS)
        val decide = cls.methodList.singleOrNull { it.returnType == PLATYPUS_DECISION && it.parameterTypes.isNotEmpty() }
            ?: throw PatchException("Platypus decision method not found")
        val decisions = ctx.dex.classDef(PLATYPUS_DECISION) ?: throw PatchException("Platypus decisions not found")
        // The constant created with the name DISABLED_BY_SABR_STREAMING_URI.
        val clinit = decisions.methods.first { it.name == "<clinit>" }.implementation!!.instructions.toList()
        val nameAt = clinit.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.StringReference)?.string == "DISABLED_BY_SABR_STREAMING_URI"
        }
        val constant = clinit.drop(nameAt).first { it.opcode == Opcode.SPUT_OBJECT }.let { (it as ReferenceInstruction).reference as FieldReference }
        val code = decide.code
        if (code.registerCount - decide.parameterRegisterCount < 1) throw PatchException("No free register in Platypus decision")
        val original = code.newLabelForIndex(0)
        listOf(
            BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, parseMethodReference(PLATYPUS_HOOK)),
            BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
            BuilderInstruction21t(Opcode.IF_EQZ, 0, original),
            BuilderInstruction21c(Opcode.SGET_OBJECT, 0, constant),
            BuilderInstruction11x(Opcode.RETURN_OBJECT, 0),
        ).asReversed().forEach { code.addInstruction(0, it) }
        ctx.log("  Yerel oynatıcı (Platypus): ${decide.name}() → ${constant.name}")
    }

    /** Media requests carry a body made for YouTube's own streams; drop it for replaced ones. */
    private fun hookMediaRequest(ctx: PatchContext) {
        val hit = ctx.dex.single(mediaRequest)
        val (uri, method, body) = mediaRequestFields(hit.method)!!
        val ctor = ctx.dex.edit(hit)
        val code = ctor.code
        if (code.registerCount - ctor.parameterRegisterCount < 4) throw PatchException("Media request: too few registers")
        val thisRegister = code.parameterRegister(ctor, 0)
        for (index in code.returnIndexes().asReversed()) {
            code.insertAtTarget(
                index,
                listOf(
                    BuilderInstruction32x(Opcode.MOVE_OBJECT_16, 0, thisRegister),
                    BuilderInstruction22c(Opcode.IGET_OBJECT, 1, 0, uri),
                    BuilderInstruction22c(Opcode.IGET, 2, 0, method),
                    BuilderInstruction22c(Opcode.IGET_OBJECT, 3, 0, body),
                    BuilderInstruction35c(Opcode.INVOKE_STATIC, 3, 1, 2, 3, 0, 0, parseMethodReference(MEDIA_REQUEST_HOOK)),
                    BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
                    BuilderInstruction22c(Opcode.IPUT_OBJECT, 1, 0, body),
                ),
            )
        }
        ctx.log("  Medya isteği: ${hit.classType}-><init>")
    }

    /** Passes the value read right after [literal] through the extension. */
    private fun overrideFlag(code: MutableMethodImplementation, literal: Long): Int {
        var changed = 0
        val ins = code.instructions
        val positions = ins.indices.filter {
            (ins[it] as? com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)?.wideLiteral == literal
        }
        for (at in positions.asReversed()) {
            val result = (at + 1..minOf(at + 6, ins.lastIndex)).firstOrNull { ins[it].opcode == Opcode.MOVE_RESULT } ?: continue
            val register = (ins[result] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
            if (register > 15) continue
            listOf(
                BuilderInstruction35c(Opcode.INVOKE_STATIC, 1, register, 0, 0, 0, 0, parseMethodReference(FLAG_HOOK)),
                BuilderInstruction11x(Opcode.MOVE_RESULT, register),
            ).asReversed().forEach { code.addInstruction(result + 1, it) }
            changed++
        }
        return changed
    }

    /** Adds `String nxVideoId()` to the player response class. */
    private fun addVideoIdMethod(ctx: PatchContext, responseType: String, details: FieldReference, videoId: FieldReference) {
        val code = MutableMethodImplementation(2) // v0, p0 = v1
        code.addInstruction(BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 1, details))
        code.addInstruction(BuilderInstruction22c(Opcode.IGET_OBJECT, 0, 0, videoId))
        code.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
        code.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 0, 0))
        code.addInstruction(BuilderInstruction11x(Opcode.RETURN_OBJECT, 0))
        // Missing video details: return null.
        code.addInstruction(1, BuilderInstruction21t(Opcode.IF_EQZ, 0, code.newLabelForIndex(3)))
        ctx.dex.edit(responseType).addMethod(
            ImmutableMethod(
                responseType, "nxVideoId", emptyList(), "Ljava/lang/String;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, code,
            ),
        )
    }
}
