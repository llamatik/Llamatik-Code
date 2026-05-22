def reverse(s):
    return s[::-1]

def is_palindrome(s):
    return s.lower() == s.lower()[::-1]

def word_count(s):
    return len(s.split())
