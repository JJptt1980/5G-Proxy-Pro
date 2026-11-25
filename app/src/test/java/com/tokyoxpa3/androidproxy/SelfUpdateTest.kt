package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfUpdateTest {
    private val sha = "a".repeat(64)
    private val url = "https://github.com/${SelfUpdate.REPO}/releases/download/v1.7.0-gw-23/${SelfUpdate.APK_NAME}"

    @Test fun parsesManifest() {
        val m = SelfUpdate.parse(
            """{"versionCode":23,"versionName":"1.7.0-gw","apkUrl":"$url","sha256":"${sha.uppercase()}","notes":"x"}"""
        )!!
        assertEquals(23L, m.versionCode)
        assertEquals("1.7.0-gw", m.versionName)
        assertEquals(sha, m.sha256)
        assertTrue(SelfUpdate.isTrusted(m))
    }

    @Test fun rejectsBadJson() {
        assertNull(SelfUpdate.parse("not json"))
        assertNull(SelfUpdate.parse("""{"versionName":"1"}"""))
    }

    @Test fun rejectsOtherHostsAndBadChecksum() {
        val good = SelfUpdate.Manifest(23, "x", url, sha, "")
        assertFalse(SelfUpdate.isTrusted(good.copy(apkUrl = "https://evil.example/x.apk")))
        assertFalse(SelfUpdate.isTrusted(good.copy(apkUrl = "https://github.com/someone/else/releases/download/a/b.apk")))
        assertFalse(SelfUpdate.isTrusted(good.copy(apkUrl = url.replace("https", "http"))))
        assertFalse(SelfUpdate.isTrusted(good.copy(sha256 = "abc")))
    }

    @Test fun newerOnlyWhenCodeIsHigher() {
        val m = SelfUpdate.Manifest(23, "x", url, sha, "")
        assertTrue(SelfUpdate.isNewer(m, 22))
        assertFalse(SelfUpdate.isNewer(m, 23))
    }

    @Test fun percentAndGate() {
        assertNull(SelfUpdate.percent(5, -1))
        assertEquals(50, SelfUpdate.percent(5, 10))
        assertTrue(SelfUpdate.enabledFor(android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE))
        assertFalse(SelfUpdate.enabledFor(0))
    }
}
