package geometry

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class CircleTest {

    @Test
    fun `area is correct`() {
        assertEquals(PI * 4.0, Circle(2.0).area(), 0.0001)
    }

    @Test
    fun `circumference is correct`() {
        assertEquals(2 * PI * 3.0, Circle(3.0).circumference(), 0.0001)
    }
}
