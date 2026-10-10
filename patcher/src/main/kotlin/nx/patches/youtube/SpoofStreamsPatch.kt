package nx.patches.youtube

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
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
import nx.patcher.insertAt
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

    override val filters = listOf(responseModel)

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
