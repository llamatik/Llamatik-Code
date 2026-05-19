This is a real open-source Kotlin math expression evaluator (Keval). The test suite has a failing test.

The expression `"(3+1)) - 2"` (note the extra closing parenthesis) should throw `KevalInvalidExpressionException`, but instead it silently evaluates to `4.0` and ignores the trailing `) - 2`.

Find the bug in the parser and fix it. Do not change the test file.
