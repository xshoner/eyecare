package com.neurolens.eyecare.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeDenseExporterTest {
    @Test fun currentAndFutureSessionsExposeRawEyeCsvExport() {
        for (version in listOf("0.3.1-alpha", "0.4.1-alpha", "0.5.0-alpha",
            "0.5.1-alpha", "0.5.2-alpha", "0.5.3-alpha", "0.6.0-alpha")) {
            assertTrue("Expected eye export for $version", EyeDenseExporter.supportsSession(version))
        }
    }

    @Test fun sessionsBeforeEyeSamplingDoNotOfferCsvExport() {
        for (version in listOf("0.2.1-alpha", "0.3.0-alpha", "unknown", "")) {
            assertFalse("Unexpected eye export for $version", EyeDenseExporter.supportsSession(version))
        }
    }
}
