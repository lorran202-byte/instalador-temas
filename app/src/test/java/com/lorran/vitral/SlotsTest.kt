package com.lorran.vitral

import org.junit.Assert.*
import org.junit.Test

class SlotsTest {
    private val backup = """
        {
          "localId": "413bd568-d6c2-48a0-bf3b-201d7fea2da1",
          "onlineId": null,
          "assemblyId": null,
          "productId": null,
          "hash": "a173be4d1bc6b81899a8b58370814dc0b3bd62a7",
          "size": 108518994,
          "titles": {
            "fallback": "Backup do tema"
          },
          "builtInThumbnails": { "en_US": ["en_US_lockscreen_0.jpg"] },
          "subResources": [
            { "localId": "6f6ab5d9-52a7-4f68-add8-d796e50ac2bd", "resourceCode": "wallpaper", "extraMeta": {}, "metaPath": null },
            { "localId": "d3e91a0d-67f1-4684-ab6a-8f35f6b86c64", "resourceCode": "lockstyle", "extraMeta": {}, "metaPath": null },
            { "localId": "cd2d365e-965c-47c3-8847-761d0e9d814e", "resourceCode": "lockscreen", "extraMeta": {}, "metaPath": null },
            { "localId": "0d83b170-8ec0-484a-bd23-2c21797108fe", "resourceCode": "icons", "extraMeta": {}, "metaPath": null }
          ],
          "rightsPath": null
        }
    """.trimIndent()

    private val store = """{"localId":"66bddc66","onlineId":"dc2d8da0","assemblyId":"c45a","productId":"c45a",
        "titles":{"en_US":"Silent Voyage","fallback":"Silent Voyage"},"subResources":[{"localId":"x","resourceCode":"icons","extraMeta":{}}]}"""

    @Test fun parsesLocalBackup() {
        val s = Slots.parse("413bd568-d6c2-48a0-bf3b-201d7fea2da1.mrm", 1000, backup)!!
        assertEquals("413bd568-d6c2-48a0-bf3b-201d7fea2da1", s.id)
        assertEquals("Backup do tema", s.title)
        assertTrue(s.local)
        assertEquals("0d83b170-8ec0-484a-bd23-2c21797108fe", s.parts["icons"])
        assertEquals("d3e91a0d-67f1-4684-ab6a-8f35f6b86c64", s.parts["lockstyle"])
        assertEquals(4, s.parts.size)
    }

    @Test fun storeThemeIsNotLocal() {
        val s = Slots.parse("66bddc66.mrm", 0, store)!!
        assertEquals("Silent Voyage", s.title)
        assertFalse(s.local)
    }

    @Test fun splitsDump() {
        val out = "\n@@VITRAL@@a.mrm|1700000000\n{\"x\":1}\n@@VITRAL@@b.mrm|1800000000\n{\"y\":2}"
        val parts = Slots.splitDump(out)
        assertEquals(2, parts.size)
        assertEquals("b.mrm", parts[1].first)
        assertEquals(1800000000000L, parts[1].second)
        assertEquals("{\"y\":2}", parts[1].third)
    }

    @Test fun patchesHashAndSize() {
        val p = Slots.patchMeta(backup, "abc", 42)
        assertTrue(p.contains("\"hash\": \"abc\""))
        assertTrue(p.contains("\"size\": 42"))
        assertFalse(p.contains("a173be4d1bc6b81899a8b58370814dc0b3bd62a7"))
    }
}
