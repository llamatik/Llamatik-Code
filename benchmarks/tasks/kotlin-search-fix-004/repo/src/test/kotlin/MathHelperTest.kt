import org.junit.Assert.*
import org.junit.Test

class MathHelperTest {

    @Test
    fun `add returns sum`() = assertEquals(7, MathHelper.add(3, 4))

    @Test
    fun `subtract returns difference`() = assertEquals(2, MathHelper.subtract(5, 3))

    @Test
    fun `multiply returns product`() = assertEquals(12, MathHelper.multiply(3, 4))

    @Test
    fun `divide returns quotient`() {
        assertEquals(5, MathHelper.divide(10, 2))
        assertEquals(3, MathHelper.divide(9, 3))
        assertEquals(1, MathHelper.divide(7, 7))
    }

    @Test
    fun `average returns correct mean`() {
        assertEquals(2.0, MathHelper.average(listOf(1, 2, 3)), 0.001)
        assertEquals(5.0, MathHelper.average(listOf(5)), 0.001)
    }
}
