import org.junit.Assert.*
import org.junit.Test

class CalculatorTest {

    private val calc = Calculator()

    @Test
    fun `add returns correct sum`() {
        assertEquals(5, calc.add(2, 3))
    }

    @Test
    fun `subtract returns correct difference`() {
        assertEquals(1, calc.subtract(4, 3))
    }

    @Test
    fun `multiply returns correct product`() {
        assertEquals(12, calc.multiply(3, 4))
    }

    @Test
    fun `divide returns correct quotient`() {
        assertEquals(3, calc.divide(9, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `divide throws on zero divisor`() {
        calc.divide(1, 0)
    }
}
