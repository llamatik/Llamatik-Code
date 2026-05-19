import org.junit.Assert.*
import org.junit.Test

class StringUtilsTest {

    @Test
    fun `reverse returns reversed string`() {
        assertEquals("olleh", StringUtils.reverse("hello"))
        assertEquals("", StringUtils.reverse(""))
        assertEquals("a", StringUtils.reverse("a"))
    }

    @Test
    fun `isPalindrome returns true for palindromes`() {
        assertTrue(StringUtils.isPalindrome("racecar"))
        assertTrue(StringUtils.isPalindrome("Madam"))
        assertTrue(StringUtils.isPalindrome(""))
    }

    @Test
    fun `isPalindrome returns false for non-palindromes`() {
        assertFalse(StringUtils.isPalindrome("hello"))
        assertFalse(StringUtils.isPalindrome("world"))
    }

    @Test
    fun `wordCount returns number of words`() {
        assertEquals(3, StringUtils.wordCount("one two three"))
        assertEquals(1, StringUtils.wordCount("hello"))
        assertEquals(0, StringUtils.wordCount(""))
        assertEquals(2, StringUtils.wordCount("  foo   bar  "))
    }
}
