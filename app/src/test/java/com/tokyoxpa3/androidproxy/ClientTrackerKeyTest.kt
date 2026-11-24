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

class ClientTrackerTagTest {
    private val hex = "V2126".toByteArray().joinToString("") { "%02x".format(it) }

    @Test fun parseWithTag() {
        val list = ClientTracker.parse("10.0.0.76,3,ab12cd34.$hex;10.0.0.9,5,", emptyMap())
        val a = list.first { it.ip == "10.0.0.76" }
        assertEquals("id:ab12cd34", a.key)
        assertEquals("V2126", a.reportedName)
        val b = list.first { it.ip == "10.0.0.9" }
        assertEquals("host:9", b.key)
        assertEquals(null, b.reportedName)
    }

    @Test fun oldFormatStillParses() {
        assertEquals("host:76", ClientTracker.parse("10.0.0.76,3", emptyMap()).single().key)
    }

    @Test fun badHexIgnored() {
        assertEquals(null, ClientTracker.decodeHex("zz"))
        assertEquals("中", ClientTracker.decodeHex("e4b8ad"))
    }
}

class ClientTrackerBrandTest {
    @Test fun brandParsedAndLabelled() {
        val c = ClientTracker.parse("10.0.0.9,5,,vivo", emptyMap()).single()
        assertEquals("vivo", c.brand)
        assertEquals("host:9", c.key)
        assertEquals("vivo 手機（猜的）", ClientTracker.autoName(c))
    }

    @Test fun reportedNameBeatsBrand() {
        val hex = "V2126".toByteArray().joinToString("") { "%02x".format(it) }
        val c = ClientTracker.parse("10.0.0.76,3,ab12cd34.$hex,vivo", emptyMap()).single()
        assertEquals("V2126", ClientTracker.autoName(c))
    }

    @Test fun unknownBrandIgnored() {
        val c = ClientTracker.parse("10.0.0.9,5,,", emptyMap()).single()
        assertEquals(null, ClientTracker.autoName(c))
        assertEquals(null, ClientTracker.brandLabel(ClientTracker.Client("1.1.1.1", null, 0, null, "nokia")))
    }
}
