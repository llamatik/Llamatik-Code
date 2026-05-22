import org.junit.Assert.*
import org.junit.Test

class StackTest {

    @Test
    fun `push and pop work correctly`() {
        val stack = Stack<Int>()
        stack.push(1)
        stack.push(2)
        stack.push(3)
        assertEquals(3, stack.pop())
        assertEquals(2, stack.pop())
        assertEquals(1, stack.pop())
    }

    @Test
    fun `peek returns top element without removing it`() {
        val stack = Stack<String>()
        stack.push("first")
        stack.push("second")
        assertEquals("second", stack.peek())
        assertEquals(2, stack.size())
    }

    @Test
    fun `isEmpty returns true for empty stack`() {
        val stack = Stack<Int>()
        assertTrue(stack.isEmpty())
        stack.push(42)
        assertFalse(stack.isEmpty())
    }

    @Test
    fun `size reflects number of elements`() {
        val stack = Stack<Int>()
        assertEquals(0, stack.size())
        stack.push(1)
        stack.push(2)
        assertEquals(2, stack.size())
    }
}
