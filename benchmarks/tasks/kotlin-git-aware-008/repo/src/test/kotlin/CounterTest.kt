import org.junit.Assert.*
import org.junit.Test

class CounterTest {

    @Test
    fun `initial count is zero`() {
        assertEquals(0, Counter().value())
    }

    @Test
    fun `increment increases count by one`() {
        val c = Counter()
        c.increment()
        assertEquals(1, c.value())
    }

    @Test
    fun `decrement decreases count by one`() {
        val c = Counter(5)
        c.decrement()
        assertEquals(4, c.value())
    }

    @Test
    fun `reset sets count to zero`() {
        val c = Counter(10)
        c.reset()
        assertEquals(0, c.value())
    }

    @Test
    fun `value returns current count`() {
        assertEquals(3, Counter(3).value())
    }
}
