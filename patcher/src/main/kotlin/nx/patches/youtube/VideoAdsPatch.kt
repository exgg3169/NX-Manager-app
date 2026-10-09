package nx.patches.youtube

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import nx.patcher.MethodFilter
import nx.patcher.Patch
import nx.patcher.PatchContext
import nx.patcher.PatchException
import nx.patcher.insertAt
import nx.patcher.returnEarly
import nx.patcher.single
import nx.patcher.stringValue

/**
 * Blocks video ads (before, during and after videos) in two layers:
 *
 * 1. Requests: the app tells YouTube's servers which OS it runs on. Android Automotive does
 *    not get video ads, so the client reports that OS name.
 * 2. Playback: if an ad still arrives, the methods that load and lay out ads do nothing.
 */
object VideoAdsPatch : Patch {
    override val id = "youtube-video-ads"
    override val title = "Video reklamlarını engelle"
    override val description = "Videoların başındaki, ortasındaki ve sonundaki reklamları engeller."
    override val packageName = YouTube.PACKAGE
    override val testedVersions = YouTube.TESTED_VERSIONS

    private const val AD_FREE_OS_NAME = "Android Automotive"

    // Constructor of the request "client context", which picks the reported OS name.
    private val clientContext = MethodFilter(
        "Client context",
        strings = listOf("Android Wear", "Android Automotive", "Android"),
        name = "<init>",
    )

    private val loadVideoAds = MethodFilter(
        "Video ad loader",
        strings = listOf(
            "TriggerBundle doesn't have the required metadata specified by the trigger ",
            "Ping migration no associated ping bindings for activated trigger: ",
        ),
        returnType = "V",
    )

    private val playerAdLayout = MethodFilter(
        "Player ad layout",
        strings = listOf("Bootstrapped layout construction resulted in non PlayerBytesLayout. PlayerAds count: "),
        returnType = "V", parameters = listOf("L"),
    )

    override val filters = listOf(clientContext, loadVideoAds, playerAdLayout)

    override fun apply(ctx: PatchContext) {
        ctx.dex.edit(ctx.dex.single(clientContext)).code.let { code ->
            val ins = code.instructions
            val android = ins.indexOfFirst { it.stringValue == "Android" }
            // The chosen name is stored right after the branches that pick it.
            val store = (android + 1 until ins.size).firstOrNull {
                ins[it].opcode == Opcode.IPUT_OBJECT &&
                    ((ins[it] as ReferenceInstruction).reference as FieldReference).type == "Ljava/lang/String;"
            } ?: throw PatchException("Client context: OS name field not found")
            val put = ins[store] as TwoRegisterInstruction
            val field = (ins[store] as ReferenceInstruction).reference
            // Store the ad-free OS name over the detected one.
            code.insertAt(
                store + 1,
                listOf(
                    BuilderInstruction21c(Opcode.CONST_STRING, put.registerA, ImmutableStringReference(AD_FREE_OS_NAME)),
                    BuilderInstruction22c(Opcode.IPUT_OBJECT, put.registerA, put.registerB, field),
                ),
            )
        }

        ctx.dex.edit(ctx.dex.single(loadVideoAds)).returnEarly()
        ctx.dex.edit(ctx.dex.single(playerAdLayout)).returnEarly()
        ctx.log("  Reklam istekleri ve oynatıcı reklam katmanı devre dışı")
    }
}
