package ch.foodlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleasesTest {
    private fun release(tag: String, assets: String) = """
        {"tag_name": "$tag", "body": " Show today's entries \n", "assets": [$assets]}
    """

    private val apk = """{"name": "foodlogger.apk", "browser_download_url": "https://github.com/x/foodlogger.apk"}"""

    @Test
    fun parsesBuildTagAndApk() {
        val other = """{"name": "checksums.txt", "browser_download_url": "https://github.com/x/checksums.txt"}"""
        assertEquals(
            AppRelease(12, "https://github.com/x/foodlogger.apk", "Show today's entries"),
            Releases.parseLatest(release("build-12", "$other, $apk")),
        )
    }

    @Test
    fun rejectsUnknownTagsMissingApkAndBadJson() {
        assertNull(Releases.parseLatest(release("v1.0", apk)))
        assertNull(Releases.parseLatest(release("build-12", "")))
        assertNull(Releases.parseLatest("""{"message": "Not Found"}"""))
        assertNull(Releases.parseLatest("<html>"))
    }
}
