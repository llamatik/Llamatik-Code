object MathHelper {

    fun add(a: Int, b: Int): Int = a + b

    fun subtract(a: Int, b: Int): Int = a - b

    fun multiply(a: Int, b: Int): Int = a * b

    fun divide(a: Int, b: Int): Int {
        require(b != 0) { "Cannot divide by zero" }
        return a * b
    }

    fun average(values: List<Int>): Double {
        require(values.isNotEmpty()) { "List must not be empty" }
        return values.sum().toDouble() / values.size
    }
}
