package nx.patches.youtube

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.insertAt
import nx.patcher.insertAtTarget
import nx.patcher.invokeStaticRange
import nx.patcher.methodRef
import nx.patcher.parameterRegister
import nx.patcher.returnIndexes
import nx.patcher.single
import nx.patcher.stringValue

/**
 * Gives the extension the current video id and control over the player: position, seeking and
 * speed. Bridge methods with fixed names are added to YouTube's obfuscated player class.
 */
object VideoInfoPatch : Patch {
    override val id = "youtube-video-info"
    override val title = "Video bilgisi"
    override val description = "Video kimliği ve oynatıcı kontrolü için gerekli altyapı."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(SettingsPatch)

    private const val VIDEO = "Lnx/ext/Video;"

    // Only the player controller fails to play a response without stream data with this message.
    private val playerController = MethodFilter(
        "Player controller", strings = listOf("playVideo called on player response with no videoStreamingData."),
    )

    // A class that tracks which video is playing; recognized by a flag constant in a sibling method.
    private val videoIdParent = MethodFilter(
        "Video id tracker", returnType = "[L", parameters = listOf("L"), literals = listOf(524288L),
    )

    override val filters = listOf(playerController)

    override fun apply(ctx: PatchContext) {
        val dex = ctx.dex
        val playerType = dex.single(playerController).classType
        val player = dex.edit(playerType)
        val methods = player.methodList

        // Seek: (long position, seek source) -> boolean; the only method with this message.
        val seek = methods.singleOrNull { m ->
            m.parameterTypes.size == 2 && m.parameterTypes[0] == "J" && m.returnType == "Z" &&
                m.implementation?.instructions?.any {
                    it.stringValue == "currentPositionMs." || it.stringValue == "Attempting to seek during an ad"
                } == true
        } ?: throw PatchException("Player seek method not found")
        val seekSource = seek.parameterTypes[1].toString()
        val seekSourceUnknown = dex.classDef(seekSource)?.staticFields
            ?.firstOrNull { it.type == seekSource && it.name == "a" }
            ?: dex.classDef(seekSource)?.staticFields?.firstOrNull { it.type == seekSource }
            ?: throw PatchException("Seek source not found")

        // Current position: the getter called next to the constant 69 (a player state id).
        val time = methods.firstNotNullOfOrNull { m ->
            val ins = m.implementation?.instructions?.toList() ?: return@firstNotNullOfOrNull null
            val at = ins.indexOfFirst { (it as? com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction)?.wideLiteral == 69L }
            if (at < 0) null else ins.subList(maxOf(0, at - 5), at).mapNotNull { it.methodRef }.firstOrNull {
                it.definingClass == playerType && it.returnType == "J" && it.parameterTypes.isEmpty()
            }
        } ?: throw PatchException("Player time method not found")

        // Speed: the only public (float) -> void method.
        val setSpeed = methods.singleOrNull {
            it.parameterTypes.map(CharSequence::toString) == listOf("F") && it.returnType == "V" &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Player speed method not found")

        // Bridges.
        player.addBridge("nxTime", emptyList(), "J", 3) { regs ->
            listOf(
                BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 1, regs.p0, 0, 0, 0, 0, time),
                BuilderInstruction11x(Opcode.MOVE_RESULT_WIDE, 0),
                BuilderInstruction11x(Opcode.RETURN_WIDE, 0),
            )
        }
        player.addBridge("nxSeek", listOf("J"), "Z", 4) { regs ->
            listOf(
                BuilderInstruction21c(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(seekSource, seekSourceUnknown.name, seekSource)),
                BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 4, regs.p0, regs.p0 + 1, regs.p0 + 2, 0, 0, seek),
                BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                BuilderInstruction11x(Opcode.RETURN, 0),
            )
        }
        player.addBridge("nxSetSpeed", listOf("F"), "V", 2) { regs ->
            listOf(
                BuilderInstruction35c(Opcode.INVOKE_VIRTUAL, 2, regs.p0, regs.p0 + 1, 0, 0, 0, setSpeed),
                BuilderInstruction10x(Opcode.RETURN_VOID),
            )
        }

        // Hand the player to the extension when it is created.
        for (init in methods.filter { it.name == "<init>" }) {
            val code = init.code
            for (index in code.returnIndexes().asReversed()) {
                code.insertAtTarget(index, listOf(invokeStaticRange(code.parameterRegister(init, 0), "$VIDEO->setPlayer(Ljava/lang/Object;)V")))
            }
        }

        // Report speed changes (to remember the user's choice).
        setSpeed.code.let { code ->
            code.insertAt(0, listOf(invokeStaticRange(code.parameterRegister(setSpeed, 1), "$VIDEO->onSpeedSet(F)V")))
        }

        // Video id: a method of the tracker class reads it from the player response and stores it in a map.
        val trackerType = dex.single(videoIdParent).classType
        val tracker = dex.edit(trackerType).methodList.firstOrNull { m ->
            val ins = m.implementation?.instructions?.toList() ?: return@firstOrNull false
            m.returnType == "V" && m.parameterTypes.size == 1 && ins.any {
                it.methodRef?.let { r -> r.definingClass == "Ljava/util/Map;" && r.name == "put" } == true
            }
        } ?: throw PatchException("Video id method not found")
        tracker.code.let { code ->
            val ins = code.instructions
            val put = ins.indexOfFirst { it.methodRef?.let { r -> r.definingClass == "Ljava/util/Map;" && r.name == "put" } == true }
            val keyRegister = (ins[put] as FiveRegisterInstruction).registerD
            val read = (put - 1 downTo 1).first {
                ins[it].opcode == Opcode.MOVE_RESULT_OBJECT && (ins[it] as OneRegisterInstruction).registerA == keyRegister &&
                    ins[it - 1].methodRef?.returnType == "Ljava/lang/String;"
            }
            code.insertAt(read + 1, listOf(invokeStaticRange(keyRegister, "$VIDEO->onVideoId(Ljava/lang/String;)V")))
        }

        ctx.log("  Oynatıcı: $playerType (zaman ${time.name}, ileri sarma ${seek.name}, hız ${setSpeed.name})")
    }

    private class Registers(val p0: Int)

    /** Adds `public final <name>(params)` to the class; [body] gets the register of `this`. */
    private fun nx.patcher.EditableClass.addBridge(
        name: String,
        params: List<String>,
        returnType: String,
        registers: Int,
        body: (Registers) -> List<com.android.tools.smali.dexlib2.builder.BuilderInstruction>,
    ) {
        val paramRegisters = 1 + params.sumOf { if (it == "J" || it == "D") 2L else 1L }.toInt()
        val code = MutableMethodImplementation(registers)
        body(Registers(registers - paramRegisters)).forEach(code::addInstruction)
        addMethod(
            ImmutableMethod(
                type, name, params.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, code,
            ),
        )
    }
}

/** Opens videos in the preferred quality (separately for Wi-Fi and mobile data) and speed. */
object QualitySpeedPatch : Patch {
    override val id = "youtube-quality-speed"
    override val title = "Varsayılan kalite ve hız"
    override val description = "Videoları seçtiğiniz kalitede (Wi-Fi ve mobil veri için ayrı) ve hızda açar; " +
        "değiştirdiğiniz hızı hatırlar. Ayarlar NX Ayarları'nda."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(VideoInfoPatch)

    private const val FIXED_RESOLUTION = ", initialPlaybackVideoQualityFixedResolution="

    private val startParameters = MethodFilter(
        "Playback start parameters", strings = listOf(FIXED_RESOLUTION), name = "toString",
    )

    override val filters = listOf(startParameters)

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("quality")
        val hit = ctx.dex.single(startParameters)
        val field = fieldPrintedAfter(hit.method, FIXED_RESOLUTION)
        var hooked = 0
        for (init in ctx.dex.edit(hit.classType).methodList.filter { it.name == "<init>" }) {
            val code = init.code
            val stores = code.instructions.indices.filter {
                val ins = code.instructions[it]
                ins.opcode == Opcode.IPUT_OBJECT && ((ins as ReferenceInstruction).reference as FieldReference).name == field.name
            }
            for (index in stores.asReversed()) {
                val register = (code.instructions[index] as TwoRegisterInstruction).registerA
                code.insertAtTarget(
                    index,
                    listOf(
                        invokeStaticRange(register, "Lnx/ext/Quality;->initialResolution(Ljava/lang/Object;)Ljava/lang/Object;"),
                        BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register),
                        BuilderInstruction21c(Opcode.CHECK_CAST, register, ImmutableTypeReference(field.type)),
                    ),
                )
                hooked++
            }
        }
        if (hooked == 0) throw PatchException("Initial quality field is never set")
        ctx.log("  Başlangıç kalitesi alanı: ${field.name} ($hooked yer)")
    }

    /** The field whose value toString() appends right after [label]. */
    private fun fieldPrintedAfter(toString: Method, label: String): FieldReference {
        val ins = toString.implementation!!.instructions.toList()
        val at = ins.indexOfFirst { it.stringValue == label }
        val append = (at + 2 until ins.size).first { ins[it].methodRef?.name == "append" }
        var register = (ins[append] as FiveRegisterInstruction).registerD
        // Follow String.valueOf(field) back to the field read.
        for (i in append - 1 downTo 0) {
            val cur = ins[i]
            if (cur.opcode == Opcode.MOVE_RESULT_OBJECT && (cur as OneRegisterInstruction).registerA == register) {
                register = (ins[i - 1] as FiveRegisterInstruction).registerC
            } else if (cur.opcode == Opcode.IGET_OBJECT && (cur as TwoRegisterInstruction).registerA == register) {
                return (cur as ReferenceInstruction).reference as FieldReference
            }
        }
        throw PatchException("Field for $label not found")
    }
}

/** Skips sponsor and other segments marked by the SponsorBlock community. */
object SponsorBlockPatch : Patch {
    override val id = "youtube-sponsorblock"
    override val title = "SponsorBlock"
    override val description = "Videolardaki sponsor, tanıtım, \"abone ol\" hatırlatması gibi bölümleri topluluk " +
        "verisiyle otomatik atlar (sponsor.ajay.app). Kategoriler NX Ayarları'nda."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(VideoInfoPatch)

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("sponsorblock")
        ctx.log("  Segmentler video açılınca indirilir, oynatma sırasında atlanır")
    }
}

/**
 * Adds a "Diagnostics / Test" panel to the NX settings: whether YouTube gets its playback
 * attestation token (PoToken) from microG, and the player errors that stop videos.
 */
object DiagnosticsPatch : Patch {
    override val id = "youtube-diagnostics"
    override val title = "Tanılama (test)"
    override val description = "Videolar neden duruyor? NX Ayarları'nda jeton (PoToken) durumunu ve oynatıcı " +
        "hata kodlarını gösterir, microG jeton servisini test eder."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS
    override val dependencies = listOf(SettingsPatch)

    private const val TOKEN_MANAGER =
        "Lcom/google/android/libraries/youtube/media/player/exo2/platypus/PlatypusProofOfOriginTokenManager;"
    private const val DIAG = "Lnx/ext/Diagnostics;"

    override fun apply(ctx: PatchContext) {
        ctx.manifest.addFeature("diagnostics")
        val manager = ctx.dex.edit(TOKEN_MANAGER)
        // Reads the session token: () -> byte[].
        val read = manager.methodList.singleOrNull { it.returnType == "[B" && it.parameterTypes.isEmpty() }
            ?: throw PatchException("PoToken read method not found")
        val code = read.code

        // The error reported when there is no token names the player error class.
        val missing = code.instructions.indexOfFirst { it.stringValue == "potoken.nulloninit" }
        if (missing < 0) throw PatchException("PoToken missing report not found")
        val errorClass = code.instructions.drop(missing).firstNotNullOfOrNull { ins ->
            ins.methodRef?.takeIf { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") }
        }?.definingClass ?: throw PatchException("Player error class not found")

        for (index in code.returnIndexes().asReversed()) {
            val register = (code.instructions[index] as OneRegisterInstruction).registerA
            code.insertAtTarget(index, listOf(invokeStaticRange(register, "$DIAG->onPoToken([B)V")))
        }
        val missingNow = code.instructions.indexOfFirst { it.stringValue == "potoken.nulloninit" }
        code.insertAt(missingNow, listOf(invokeStaticRange(0, "$DIAG->onPoTokenMissing()V")))

        // Every player error passes its code to this constructor.
        val errorInit = ctx.dex.edit(errorClass).methodList.singleOrNull {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
        } ?: throw PatchException("Player error constructor not found")
        errorInit.code.let { c ->
            c.insertAt(0, listOf(invokeStaticRange(c.parameterRegister(errorInit, 1), "$DIAG->onPlayerError(Ljava/lang/String;)V")))
        }
        ctx.log("  Jeton: ${read.name}(), oynatıcı hataları: $errorClass")
    }
}
