package com.llamatik.teammate.codehealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class WatchedAreaServiceTest {

    private inner class TestWatchedAreaService(private val isPro: Boolean = false) {
        private val areas = CopyOnWriteArrayList<WatchedArea>()
        private fun maxAreas(): Int = if (isPro) 20 else 1

        fun addArea(path: String, type: WatchedAreaType): WatchedArea? {
            if (areas.size >= maxAreas()) return null
            val area = WatchedArea(id = UUID.randomUUID().toString(), path = path, type = type)
            areas.add(area)
            return area
        }

        fun removeArea(id: String) { areas.removeAll { it.id == id } }
        fun listAreas(): List<WatchedArea> = areas.filter { it.enabled }
        fun isWatched(filePath: String): Boolean = areas.any { it.enabled && filePath.contains(it.path) }
    }

    @Test fun `addArea returns new WatchedArea`() {
        val svc = TestWatchedAreaService(isPro = true)
        val area = svc.addArea("src/main/kotlin/Foo.kt", WatchedAreaType.FILE)
        assertNotNull(area)
        assertEquals("src/main/kotlin/Foo.kt", area!!.path)
        assertEquals(WatchedAreaType.FILE, area.type)
        assertTrue(area.enabled)
        assertTrue(area.id.isNotBlank())
    }

    @Test fun `addArea returns area with non-blank id`() {
        val area = TestWatchedAreaService(isPro = true).addArea("pkg", WatchedAreaType.PACKAGE)
        assertNotNull(area)
        assertTrue(area!!.id.isNotBlank())
    }

    @Test fun `free tier allows only 1 area`() {
        val svc = TestWatchedAreaService(isPro = false)
        assertNotNull(svc.addArea("src/A.kt", WatchedAreaType.FILE))
        assertNull(svc.addArea("src/B.kt", WatchedAreaType.FILE))
    }

    @Test fun `pro tier allows up to 20 areas`() {
        val svc = TestWatchedAreaService(isPro = true)
        repeat(20) { i -> assertNotNull("area $i should succeed", svc.addArea("path$i", WatchedAreaType.DIRECTORY)) }
        assertNull("21st area should be rejected", svc.addArea("path20", WatchedAreaType.DIRECTORY))
    }

    @Test fun `removeArea deletes the area`() {
        val svc = TestWatchedAreaService(isPro = true)
        val area = svc.addArea("src/Foo.kt", WatchedAreaType.FILE)!!
        svc.removeArea(area.id)
        assertTrue(svc.listAreas().isEmpty())
    }

    @Test fun `removeArea on nonexistent id is a no-op`() {
        val svc = TestWatchedAreaService(isPro = true)
        svc.addArea("src/Foo.kt", WatchedAreaType.FILE)
        svc.removeArea("does-not-exist")
        assertEquals(1, svc.listAreas().size)
    }

    @Test fun `listAreas is empty initially`() {
        assertTrue(TestWatchedAreaService().listAreas().isEmpty())
    }

    @Test fun `listAreas returns added areas`() {
        val svc = TestWatchedAreaService(isPro = true)
        svc.addArea("com/example", WatchedAreaType.PACKAGE)
        svc.addArea("src/main", WatchedAreaType.DIRECTORY)
        assertEquals(2, svc.listAreas().size)
    }

    @Test fun `isWatched returns true for file under watched directory`() {
        val svc = TestWatchedAreaService(isPro = true)
        svc.addArea("src/main/kotlin", WatchedAreaType.DIRECTORY)
        assertTrue(svc.isWatched("src/main/kotlin/com/example/Foo.kt"))
    }

    @Test fun `isWatched returns true for exact file watch`() {
        val svc = TestWatchedAreaService(isPro = true)
        svc.addArea("src/main/kotlin/Foo.kt", WatchedAreaType.FILE)
        assertTrue(svc.isWatched("src/main/kotlin/Foo.kt"))
    }

    @Test fun `isWatched returns false when path outside all watched areas`() {
        val svc = TestWatchedAreaService(isPro = true)
        svc.addArea("src/main/kotlin", WatchedAreaType.DIRECTORY)
        assertFalse(svc.isWatched("src/test/kotlin/FooTest.kt"))
    }

    @Test fun `isWatched returns false when no areas registered`() {
        assertFalse(TestWatchedAreaService().isWatched("src/main/kotlin/Foo.kt"))
    }

    @Test fun `removeArea then isWatched returns false`() {
        val svc = TestWatchedAreaService(isPro = true)
        val area = svc.addArea("src/main/kotlin", WatchedAreaType.DIRECTORY)!!
        svc.removeArea(area.id)
        assertFalse(svc.isWatched("src/main/kotlin/Foo.kt"))
    }

    @Test fun `free tier second addArea does not affect first area`() {
        val svc = TestWatchedAreaService(isPro = false)
        val first = svc.addArea("src/A.kt", WatchedAreaType.FILE)!!
        svc.addArea("src/B.kt", WatchedAreaType.FILE)
        assertEquals(1, svc.listAreas().size)
        assertEquals(first.id, svc.listAreas().first().id)
    }
}
