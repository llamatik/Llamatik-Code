import org.junit.Assert.*
import org.junit.Test

class FormatterTest {

    @Test
    fun `UpperCaseFormatter implements Formatter`() {
        val formatter: Formatter = UpperCaseFormatter()
        assertEquals("HELLO", formatter.format("hello"))
    }

    @Test
    fun `format uppercases entire string`() {
        val f = UpperCaseFormatter()
        assertEquals("WORLD", f.format("world"))
        assertEquals("ABC", f.format("abc"))
        assertEquals("", f.format(""))
    }

    @Test
    fun `format handles already uppercase input`() {
        val f = UpperCaseFormatter()
        assertEquals("ALREADY", f.format("ALREADY"))
    }
}
