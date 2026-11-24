package com.tokyoxpa3.androidproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientTrackerKeyTest {
    @Test fun ipv4UsesLastOctet() {
        assertEquals("host:76", ClientTracker.hostKey("10.63.154.76"))
        assertEquals("host:76", ClientTracker.hostKey("10.195.54.76"))
    }

    @Test fun nonIpv4Unchanged() {
        assertEquals("fd00::1", ClientTracker.hostKey("fd00::1"))
        assertEquals("aa:bb:cc:dd:ee:ff", ClientTracker.hostKey("aa:bb:cc:dd:ee:ff"))
    }

    @Test fun clientKeyPrefersMac() {
        assertEquals("aa:bb", ClientTracker.Client("10.0.0.5", "aa:bb", 0).key)
        assertEquals("host:5", ClientTracker.Client("10.0.0.5", null, 0).key)
    }

    @Test fun migrateOldIpKeys() {
        val old = linkedMapOf("10.63.154.76" to "V2126", "host:9" to "平板", "10.1.1.9" to "舊平板")
        assertEquals(mapOf("host:76" to "V2126", "host:9" to "平板"), ClientTracker.migrateKeys(old))
    }
}
