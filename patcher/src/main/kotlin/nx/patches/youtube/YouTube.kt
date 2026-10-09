package nx.patches.youtube

import nx.patcher.Patch

object YouTube {
    const val PACKAGE = "com.google.android.youtube"

    /** SHA-1 of YouTube's original signing certificate, which Google's servers expect. */
    const val SIGNATURE_SHA1 = "24bb24c05e47e0aefa68a58a766179d9b613a600"

    val TESTED_VERSIONS = setOf("21.16.256")

    val patches: List<Patch> = listOf(GmsCorePatch, VideoAdsPatch)
}
