The project has a `Formatter` interface and an `UpperCaseFormatter` implementation. The tests are failing because:

1. The `Formatter` interface declares `fun format(input: String): String`, but the implementation uses `fun transform(input: String): String` instead.
2. `UpperCaseFormatter` does not implement the `Formatter` interface at all.

Fix both files so that `UpperCaseFormatter` correctly implements `Formatter` and its method is named `format`.
