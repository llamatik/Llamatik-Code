import kotlin.test.Test
import kotlin.test.assertEquals

class StackTest {
    @Test
    fun peek() {
        val stack = Stack<Int>()
        stack.push(1)
        stack.push(2)
        stack.push(3)
        assertEquals(3, stack.peek())
    }
}
