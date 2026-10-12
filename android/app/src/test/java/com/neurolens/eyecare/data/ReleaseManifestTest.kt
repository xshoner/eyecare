package com.neurolens.eyecare.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseManifestTest {
    private val sha = "a".repeat(64)
    private val link = "https://drive.google.com/file/d/abc-123/view?usp=drivesdk"

    @Test fun onlyValidatedNewerReleaseCanBeOffered() {
        val release = ReleaseManifest.validate(20, "0.9.0-alpha", sha, link)
        assertNotNull(release)
        assertFalse(ReleaseManifest.isNewer(release!!, 20))
        assertTrue(ReleaseManifest.isNewer(release, 19))
        assertNull(ReleaseManifest.validate(20, "0.9.0-alpha", sha, link.replace("drive.google.com", "evil.example")))
        assertNull(ReleaseManifest.validate(20, "0.9.0-alpha", "bad", link))
    }
}
