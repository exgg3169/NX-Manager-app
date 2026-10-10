package nx.patches.youtube

import com.android.tools.smali.dexlib2.Opcode
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.find
import nx.patcher.methodRef
import nx.patcher.overrideLiteralResult
import nx.patcher.returnEarly
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

    override fun apply(ctx: PatchContext) {
        val dex = ctx.dex

        for ((filter, value) in listOf(playbackAllowed to true, shortsPlaybackAllowed to true, kidsPolicy to null)) {
            val hit = dex.single(filter)
            dex.edit(hit).returnEarly(value)
            ctx.log("  ${filter.label}: ${hit.classType}->${hit.method.name}")
        }

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
        availabilityMethod.returnEarly(true)

        // Flags read through simple getters.
        listOf(
            FLAG_SHORTS_BACKGROUND to true,
            // Do not restart a video paused from the notification just because the app is reopened.
            FLAG_RESUME_IN_FOREGROUND to false,
            // Do not pause when the video settings menu is opened.
            FLAG_PAUSE_FOR_SETTINGS_MENU to false,
        ).forEach { (flag, value) ->
            val hits = dex.find(flagGetter(flag))
            if (hits.isEmpty()) ctx.log("  Uyarı: bayrak $flag bulunamadı")
            hits.forEach { dex.edit(it).returnEarly(value) }
        }

        // Picture-in-picture buttons stop responding after locking the screen when this flag is on.
        val pipHits = dex.find(MethodFilter("PiP input consumer flag", literals = listOf(FLAG_PIP_INPUT_CONSUMER)))
        val pipChanged = pipHits.sumOf { dex.edit(it).code.overrideLiteralResult(FLAG_PIP_INPUT_CONSUMER, false) }
        if (pipChanged == 0) ctx.log("  Uyarı: PiP bayrağı bulunamadı")

        ctx.log("  Arka plan oynatma kısıtlamaları kaldırıldı")
    }
}
