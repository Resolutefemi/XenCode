package com.xencode.feature.workspace.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryListingCacheTest {

    private fun entry(name: String) = FileEntry(name = name, isDirectory = false, size = 0, lastModified = 0)

    @Test
    fun missReturnsNull_hitAfterPut() {
        val cache = DirectoryListingCache()
        assertNull(cache.get("/ws/a"))
        cache.put("/ws/a", listOf(entry("f1"), entry("f2")))
        assertEquals(listOf("f1", "f2"), cache.get("/ws/a")?.map { it.name })
    }

    @Test
    fun invalidateRemovesOnlyGivenDirectory() {
        val cache = DirectoryListingCache()
        cache.put("/ws/a", listOf(entry("f")))
        cache.put("/ws/b", listOf(entry("f")))
        cache.invalidate("/ws/a")
        assertNull(cache.get("/ws/a"))
        assertEquals(1, cache.get("/ws/b")?.size)
    }

    @Test
    fun invalidateTreeAlsoRemovesAllDescendants() {
        val cache = DirectoryListingCache()
        cache.put("/ws/d", listOf(entry("f")))
        cache.put("/ws/d/sub", listOf(entry("f")))
        cache.put("/ws/d/sub/deep", listOf(entry("f")))
        cache.put("/ws/dx", listOf(entry("f"))) // similar prefix but not a descendant
        cache.invalidateTree("/ws/d")
        assertNull(cache.get("/ws/d"))
        assertNull(cache.get("/ws/d/sub"))
        assertNull(cache.get("/ws/d/sub/deep"))
        assertEquals(1, cache.get("/ws/dx")?.size)
    }

    @Test
    fun invalidateWithEmptyPathIsNoop() {
        val cache = DirectoryListingCache()
        cache.put("/ws/a", listOf(entry("f")))
        cache.invalidate("")
        assertEquals(1, cache.size())
    }

    @Test
    fun clearRemovesEverything() {
        val cache = DirectoryListingCache()
        cache.put("/ws/a", listOf(entry("f")))
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get("/ws/a"))
    }

    @Test
    fun overCapacityEvictsOldestInInsertionOrder() {
        val cache = DirectoryListingCache(maxEntries = 3)
        cache.put("/ws/1", listOf(entry("f")))
        cache.put("/ws/2", listOf(entry("f")))
        cache.put("/ws/3", listOf(entry("f")))
        cache.put("/ws/4", listOf(entry("f")))
        assertNull(cache.get("/ws/1"))
        assertEquals(3, cache.size())
        assertEquals(1, cache.get("/ws/4")?.size)
    }

    @Test
    fun repeatedPutSamePathDoesNotGrowCapacity() {
        val cache = DirectoryListingCache()
        repeat(10) { cache.put("/ws/a", listOf(entry("f$it"))) }
        assertEquals(1, cache.size())
        assertEquals(listOf("f9"), cache.get("/ws/a")?.map { it.name })
    }

    @Test
    fun concurrentReadWriteThrowsNoException() {
        val cache = DirectoryListingCache(maxEntries = 16)
        val threads = (1..8).map { t ->
            Thread {
                repeat(200) { i ->
                    cache.put("/ws/$t/$i", listOf(entry("f")))
                    cache.get("/ws/$t/$i")
                    if (i % 7 == 0) cache.invalidateTree("/ws/$t")
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue("capacity should stay bounded, actual=${cache.size()}", cache.size() <= 16)
    }
}
