object NumberUtils {

    fun isEven(n: Int): Boolean = n % 2 == 0

    fun isOdd(n: Int): Boolean = n % 2 != 0

    fun abs(n: Int): Int = if (n < 0) -n else n

    fun max(a: Int, b: Int): Int = if (a >= b) a else b

    fun min(a: Int, b: Int): Int = if (a <= b) a else b
}
