import kotlin.test.Test
import kotlin.test.assertEquals

class CalculatorTest {
    private val calc = Calculator()

    @Test fun add() = assertEquals(5, calc.add(2, 3))
    @Test fun subtract() = assertEquals(1, calc.subtract(3, 2))
    @Test fun multiply() = assertEquals(6, calc.multiply(2, 3))
    @Test fun divide() = assertEquals(2, calc.divide(6, 3))
}
