package nx.patches.youtube

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.find
import nx.patcher.insertAtTarget
import nx.patcher.invokeStaticRange
import nx.patcher.parameterRegister
import nx.patcher.parameterRegisterCount
import nx.patcher.parseMethodReference
import nx.patcher.returnIndexes
import nx.patcher.methodRef
import nx.patcher.overrideLiteralResult
import nx.patcher.single

/**
 * Keeps videos playing when the app is in the background or the screen is off, which YouTube
 * normally reserves for Premium. The player asks a few yes/no questions before continuing in the
 * background; this patch answers them all with yes.
 */
object BackgroundPlaybackPatch : Patch {
    override val id = "youtube-background-playback"
    override val title = "Arka planda oynatma"
    override val description = "Uygulamadan çıkınca veya ekran kapanınca video çalmaya devam eder " +
        "(Shorts ve çocuk videoları dahil)."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS

    // Feature flags (server-controlled switches) read by the player.
    private const val FLAG_SHORTS_BACKGROUND = 45415425L
    private const val FLAG_RESUME_IN_FOREGROUND = 45770945L
    private const val FLAG_PAUSE_FOR_SETTINGS_MENU = 45741823L
    private const val FLAG_PIP_INPUT_CONSUMER = 45638483L

    private const val SETTINGS_TITLE = "string/pref_background_and_offline_category"
    private const val SETTINGS_TITLE_BACKGROUND_ONLY = "string/pref_background_category"
    override val resources = listOf(SETTINGS_TITLE, SETTINGS_TITLE_BACKGROUND_ONLY)

    // The main "may this video keep playing in the background?" check.
    private val playbackAllowed = MethodFilter(
        "Background playback check",
        returnType = "Z", parameters = listOf("L"), isStatic = true,
        opcodes = listOf(
            Opcode.CONST_4, Opcode.IF_EQZ, Opcode.IGET, Opcode.AND_INT_LIT16, Opcode.IF_EQZ,
            Opcode.IGET_OBJECT, Opcode.IF_NEZ, Opcode.SGET_OBJECT, Opcode.IGET, Opcode.CONST, Opcode.IF_NE,
        ),
    )

    // The same check for Shorts: it reads a boolean policy field, unlike a sibling method
    // that loads the same constant.
    private val shortsPlaybackAllowed = MethodFilter(
        "Shorts background playback check",
        returnType = "Z", parameters = listOf("L"), isStatic = true,
        literals = listOf(151635310L),
        extra = { m -> m.implementation?.instructions?.any { it.opcode == Opcode.IGET_BOOLEAN } == true },
    )

    // Blocks background playback of videos made for kids.
    private val kidsPolicy = MethodFilter(
        "Kids background playback policy",
        returnType = "V", parameters = listOf("I", "L", "L"),
        literals = listOf(5L),
        opcodes = listOf(
            Opcode.CONST_4, Opcode.IF_NE, Opcode.SGET_OBJECT, Opcode.IF_NE, Opcode.IGET, Opcode.CONST_4,
            Opcode.IF_NE, Opcode.IGET_OBJECT,
        ),
    )

    private fun flagGetter(flag: Long) = MethodFilter("Feature flag $flag", returnType = "Z", parameters = emptyList(), literals = listOf(flag))

    private const val EXT = "Lnx/ext/Background;"
    private const val OVERLAYS_LAYOUT = "Lcom/google/android/apps/youtube/app/common/player/overlay/YouTubePlayerOverlaysLayout;"

    // The player type enum: NONE, HIDDEN, WATCH_WHILE_MAXIMIZED, INLINE_MINIMAL (feed preview), …
    private val playerTypeEnum = MethodFilter(
        "Player type enum", strings = listOf("INLINE_MINIMAL", "WATCH_WHILE_MAXIMIZED"), name = "<clinit>",
    )

    override val filters = listOf(playbackAllowed, shortsPlaybackAllowed, kidsPolicy, playerTypeEnum)

    override fun apply(ctx: PatchContext) {
        val dex = ctx.dex
        ctx.manifest.addFeature("background")

        // The player reports its type to the overlay layout; background playback is allowed for the
        // opened player only, not for feed previews.
        val typeEnum = dex.single(playerTypeEnum).classType
        val setType = dex.edit(OVERLAYS_LAYOUT).methodList.singleOrNull {
            it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(typeEnum) &&
                com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: throw PatchException("Player type setter not found")
        setType.code.insertAtTarget(0, listOf(invokeStaticRange(setType.code.parameterRegister(setType, 1), "${EXT}->setPlayerType(Ljava/lang/Enum;)V")))

        // The answers to "may this keep playing in the background?" go through the extension.
        for ((filter, hook) in listOf(playbackAllowed to "allowed", shortsPlaybackAllowed to "shortsAllowed")) {
            val hit = dex.single(filter)
            hookReturns(dex.edit(hit), "${EXT}->$hook(Z)Z")
            ctx.log("  ${filter.label}: ${hit.classType}->${hit.method.name}")
        }

        // Kids videos: skip the policy that blocks them.
        val kidsHit = dex.single(kidsPolicy)
        val kids = dex.edit(kidsHit)
        kids.code.let { code ->
            if (code.registerCount - kids.parameterRegisterCount < 1) throw PatchException("Kids policy: no free register")
            val original = code.newLabelForIndex(0)
            listOf(
                BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, parseMethodReference("${EXT}->skipKidsPolicy()Z")),
                BuilderInstruction11x(Opcode.MOVE_RESULT, 0),
                BuilderInstruction21t(Opcode.IF_EQZ, 0, original),
                BuilderInstruction10x(Opcode.RETURN_VOID),
            ).asReversed().forEach { code.addInstruction(0, it) }
        }
        ctx.log("  ${kidsPolicy.label}: ${kidsHit.classType}->${kidsHit.method.name}")

        // YouTube settings show "Background play" only when the account may use it.
        val settingsTitle = MethodFilter(
            "Background settings title",
            returnType = "Ljava/lang/String;", parameters = emptyList(),
            literals = listOf(ctx.resourceId(SETTINGS_TITLE), ctx.resourceId(SETTINGS_TITLE_BACKGROUND_ONLY)),
        )
        val title = dex.edit(dex.single(settingsTitle))
        val availability = title.code.instructions
            .mapNotNull { it.methodRef }
            .filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }
            .getOrNull(1) ?: throw PatchException("Background settings availability check not found")
        val availabilityMethod = dex.edit(availability.definingClass).methodList.firstOrNull {
            it.name == availability.name && it.returnType == "Z" && it.parameterTypes.isEmpty()
        } ?: throw PatchException("Background settings availability method not found")
        hookReturns(availabilityMethod, "${EXT}->enableFlag(Z)Z")

        // Flags read through simple getters.
        listOf(
            FLAG_SHORTS_BACKGROUND to "shortsAllowed",
            // Do not restart a video paused from the notification just because the app is reopened.
            FLAG_RESUME_IN_FOREGROUND to "disableFlag",
            // Do not pause when the video settings menu is opened.
            FLAG_PAUSE_FOR_SETTINGS_MENU to "disableFlag",
        ).forEach { (flag, hook) ->
            val hits = dex.find(flagGetter(flag))
            if (hits.isEmpty()) ctx.log("  Uyarı: bayrak $flag bulunamadı")
            hits.forEach { hookReturns(dex.edit(it), "${EXT}->$hook(Z)Z") }
        }

        // Picture-in-picture buttons stop responding after locking the screen when this flag is on.
        val pipHits = dex.find(MethodFilter("PiP input consumer flag", literals = listOf(FLAG_PIP_INPUT_CONSUMER)))
        val pipChanged = pipHits.sumOf { dex.edit(it).code.overrideLiteralResult(FLAG_PIP_INPUT_CONSUMER, false) }
        if (pipChanged == 0) ctx.log("  Uyarı: PiP bayrağı bulunamadı")

        ctx.log("  Arka plan oynatma kısıtlamaları kaldırıldı (NX Ayarları'ndan kapatılabilir)")
    }

    /** Passes every boolean the method returns through [hook]. */
    private fun hookReturns(method: nx.patcher.EditableMethod, hook: String) {
        val code = method.code
        for (index in code.returnIndexes().asReversed()) {
            val register = (code.instructions[index] as com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
            code.insertAtTarget(index, listOf(invokeStaticRange(register, hook), BuilderInstruction11x(Opcode.MOVE_RESULT, register)))
        }
    }
}
