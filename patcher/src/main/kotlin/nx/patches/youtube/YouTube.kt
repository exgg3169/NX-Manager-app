package nx.patches.youtube

import nx.patcher.Patch

object YouTube {
    const val PACKAGE = "com.google.android.youtube"

    /** SHA-1 of YouTube's original signing certificate, which Google's servers expect. */
    const val SIGNATURE_SHA1 = "24bb24c05e47e0aefa68a58a766179d9b613a600"

    /** SHA-256 of the certificates Google signs YouTube with (current and pre-rotation). */
    val SIGNING_CERTS = setOf(
        "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
        "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
    )

    val TESTED_VERSIONS = setOf("21.16.256")

    val patches: List<Patch> = listOf(GmsCorePatch, AppNamePatch, SettingsPatch, VideoAdsPatch, FeedAdsPatch, HideShortsPatch, QualitySpeedPatch, SpeedMenuPatch, SponsorBlockPatch, ReturnYouTubeDislikePatch, BackgroundPlaybackPatch, SpoofStreamsPatch, DiagnosticsPatch)
}
