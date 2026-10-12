package com.neurolens.eyecare.data

import org.junit.Assert.*
import org.junit.Test

class CollectorClientTest {
    private val key = "a".repeat(48)
    @Test fun acceptsOnlyExplicitPrivateAddressAndKey() {
        assertEquals("http://192.168.1.2:8765/api/import", CollectorClient.parse("http://192.168.1.2:8765/#$key").endpoint)
        assertEquals(key, CollectorClient.parse("http://127.0.0.1:8765/#$key").token)
        listOf("http://example.com:8765/#$key", "http://8.8.8.8:8765/#$key", "http://192.168.1.2:8765/", "http://user@192.168.1.2:8765/#$key", "http://192.168.1.2:8765/path#$key", "http://192.168.1.2:8765/?token=x#$key").forEach {
            assertThrows(IllegalArgumentException::class.java) { CollectorClient.parse(it) }
        }
    }
}
