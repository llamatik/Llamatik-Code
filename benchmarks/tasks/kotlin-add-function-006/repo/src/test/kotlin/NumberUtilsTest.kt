import org.junit.Assert.*
import org.junit.Test

class NumberUtilsTest {

    @Test
    fun `isEven returns true for even numbers`() {
        assertTrue(NumberUtils.isEven(0))
        assertTrue(NumberUtils.isEven(4))
        assertFalse(NumberUtils.isEven(3))
    }

    @Test
    fun `abs returns absolute value`() {
        assertEquals(5, NumberUtils.abs(-5))
        assertEquals(5, NumberUtils.abs(5))
        assertEquals(0, NumberUtils.abs(0))
    }

    @Test
    fun `max and min return correct values`() {
        assertEquals(7, NumberUtils.max(3, 7))
        assertEquals(3, NumberUtils.min(3, 7))
    }

    @Test
    fun `clamp returns value within range`() {
        assertEquals(5, NumberUtils.clamp(5, 1, 10))
        assertEquals(1, NumberUtils.clamp(-3, 1, 10))
        assertEquals(10, NumberUtils.clamp(20, 1, 10))
        assertEquals(3, NumberUtils.clamp(3, 3, 3))
    }
}
