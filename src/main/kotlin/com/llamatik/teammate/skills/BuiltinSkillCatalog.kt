package com.llamatik.teammate.skills

object BuiltinSkillCatalog {

    val all: List<SkillDescriptor> = listOf(

        SkillDescriptor(
            id = "kotlin-unit-tests",
            displayName = "Kotlin Unit Tests",
            description = "Write comprehensive JUnit 5 tests with MockK for Kotlin code.",
            category = "Testing",
            tags = listOf("testing", "kotlin", "junit5", "mockk"),
            prompt = """
When writing Kotlin unit tests follow these rules:
- Use JUnit 5 (@Test, @BeforeEach, @AfterEach, @Nested) and MockK (every { } / verify { } / coEvery { }).
- Name tests with backtick identifiers, e.g.: fun `should return X when Y`().
- Cover: happy path, edge cases (null, empty, boundary values), and error/exception paths.
- Use assertj-core (assertThat) or JUnit 5 assertEquals/assertThrows — no Truth or Hamcrest unless already in project.
- Mock all external dependencies; test only the class under test in isolation.
- Keep each @Test independent — no shared mutable state between tests.
- For suspend functions, wrap with runTest { } from kotlinx-coroutines-test.
- Group related tests in @Nested inner classes named after the method being tested.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "android-mvvm",
            displayName = "Android MVVM",
            description = "Android MVVM architecture with ViewModel, StateFlow, and Repository.",
            category = "Android",
            tags = listOf("android", "mvvm", "viewmodel", "stateflow", "repository"),
            prompt = """
When writing Android code follow MVVM architecture:
- ViewModel: expose UI state via StateFlow<UiState>, use sealed class UiState with Loading/Success/Error.
- Repository: abstract data sources, return Flow<Result<T>> or suspend fun returning Result<T>.
- Use Hilt for DI: @HiltViewModel on ViewModels, @AndroidEntryPoint on Activities/Fragments.
- Collect StateFlow in Activity/Fragment with lifecycleScope.launch { repeatOnLifecycle(STARTED) { ... } }.
- Never access Context or Android framework in ViewModel; use use-cases or managers injected via DI.
- UiState changes: use data class copy() in ViewModel, emit with _uiState.update { it.copy(...) }.
- For one-shot events (navigation, snackbar), use SharedFlow<Event>.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "clean-architecture",
            displayName = "Clean Architecture",
            description = "Domain/Data/Presentation layers with use cases and clear dependency rules.",
            category = "Architecture",
            tags = listOf("architecture", "clean", "domain", "use-cases", "ddd"),
            prompt = """
When designing code use Clean Architecture layering:
- domain/: pure Kotlin — entities, repository interfaces, use cases (no Android/framework imports).
- data/: implements domain repositories, contains API clients, DAOs, mappers.
- presentation/: ViewModels, Activities/Fragments/Composables — depends on domain, never on data.
- Use cases (Interactors): single-responsibility, each in its own class, execute() or invoke() operator.
- Repository pattern: interface in domain, implementation in data — domain never depends on data.
- Map between layers: data entities ↔ domain models ↔ UI models using dedicated mapper objects.
- Dependency rule: dependencies only point inward (presentation → domain ← data).
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "kotlin-coroutines",
            displayName = "Kotlin Coroutines & Flow",
            description = "Async code with structured concurrency, Flow, and proper cancellation.",
            category = "Kotlin",
            tags = listOf("coroutines", "flow", "suspend", "async", "kotlin"),
            prompt = """
When writing async Kotlin code:
- Prefer suspend functions over callbacks; use Flow for streams of values.
- Use structured concurrency: launch/async within a CoroutineScope, never GlobalScope.
- Use withContext(Dispatchers.IO) for I/O, Dispatchers.Default for CPU-bound work.
- Handle errors with try/catch in suspend functions or catch operator on Flow.
- Use StateFlow for hot state streams and SharedFlow for one-shot events.
- Avoid blocking calls (Thread.sleep, .get()) in coroutines — use delay() and suspend alternatives.
- Cancel cooperatively: check isActive or use ensureActive() in loops; clean up in finally blocks.
- Prefer flow { emit(...) } over callbackFlow unless wrapping a callback-based API.
- In tests, use runTest { } and TestCoroutineDispatcher/UnconfinedTestDispatcher.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "jetpack-compose",
            displayName = "Jetpack Compose",
            description = "Composable functions, state hoisting, and Compose best practices.",
            category = "Android",
            tags = listOf("compose", "android", "ui", "kotlin", "state"),
            prompt = """
When writing Jetpack Compose UI:
- Hoist state: stateless composables accept state + lambdas (onXxx); stateful composables own state via remember/collectAsStateWithLifecycle.
- Keep composables small and focused; extract reusable components into their own @Composable functions.
- Use LazyColumn/LazyRow for lists — never Column with forEach over large data sets.
- Use derivedStateOf { } to avoid unnecessary recompositions when computing derived state.
- Prefer Modifier as the last non-lambda parameter; use .fillMaxWidth()/.fillMaxSize() appropriately.
- Side effects: LaunchedEffect for coroutines, DisposableEffect for cleanup, rememberCoroutineScope for event handlers.
- Add @Preview annotations with sample data for every composable.
- Avoid business logic in composables — delegate to ViewModel via lambda callbacks.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "error-handling",
            displayName = "Error Handling",
            description = "Robust error handling with Result, sealed classes, and explicit failure paths.",
            category = "Kotlin",
            tags = listOf("error-handling", "result", "sealed", "exceptions", "kotlin"),
            prompt = """
When handling errors in Kotlin:
- Use sealed class for domain errors: sealed class DomainError { data class NotFound(val id: String): DomainError() ... }
- Return Result<T> or Either<Error, T> from use cases and repositories — never throw for expected errors.
- Use runCatching { } to convert throws to Result; map, recover, getOrElse for transformations.
- Distinguish: expected errors (model as sealed class, return them) vs unexpected errors (let them propagate as exceptions to a top-level handler).
- In ViewModels/UI layer, map domain errors to user-readable messages; log unexpected errors.
- Never swallow exceptions with empty catch blocks; at minimum log and rethrow.
- Use require()/check()/error() for precondition violations inside functions, not for user-visible errors.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "solid-principles",
            displayName = "SOLID Principles",
            description = "Apply SRP, OCP, LSP, ISP, DIP to produce maintainable Kotlin code.",
            category = "Design",
            tags = listOf("solid", "design-patterns", "oop", "refactoring", "kotlin"),
            prompt = """
Apply SOLID principles to all code:
- SRP: each class has exactly one reason to change; extract responsibilities into separate classes/interfaces.
- OCP: extend behavior by adding new classes or functions, not by modifying existing ones; use strategy/decorator patterns.
- LSP: subtypes must be substitutable for their base type; avoid overriding methods in ways that change their contract.
- ISP: prefer many small focused interfaces over one large one; Kotlin sealed interfaces work well here.
- DIP: high-level modules depend on abstractions (interfaces), not concrete implementations; inject dependencies.
- Use constructor injection; avoid service locators and static access to mutable global state.
- Prefer composition over inheritance; use delegation (by keyword in Kotlin) instead of extending classes for reuse.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "kdoc-documentation",
            displayName = "KDoc Documentation",
            description = "Add thorough KDoc documentation following Kotlin conventions.",
            category = "Documentation",
            tags = listOf("documentation", "kdoc", "comments", "kotlin"),
            prompt = """
When adding documentation write proper KDoc:
- Every public class, function, and property needs a KDoc comment.
- First sentence is a summary — no redundant "This function does..." phrasing.
- Use @param for each parameter, @return for return value, @throws for checked/documented exceptions.
- Use [TypeName] and [functionName] for in-text references (KDoc link syntax).
- Mark deprecated APIs with @Deprecated("reason", ReplaceWith("newApi()")).
- Document non-obvious behavior, performance characteristics, and thread-safety guarantees.
- Keep comments at the level of WHY, not WHAT — code should be self-documenting for the what.
- Add @since tags when documenting public APIs that version-track changes.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "security-review",
            displayName = "Security Review",
            description = "Identify and fix OWASP top 10 vulnerabilities and insecure patterns.",
            category = "Security",
            tags = listOf("security", "owasp", "injection", "auth", "validation"),
            prompt = """
When reviewing or writing code from a security perspective:
- Input validation: validate ALL external input at the system boundary; reject early, whitelist over blacklist.
- SQL injection: use parameterized queries or Room @Query with bound parameters — never string concatenation.
- XSS: escape all user-supplied content before rendering in HTML/WebView.
- Sensitive data: never log passwords, tokens, PII; use Android Keystore / encrypted SharedPreferences.
- Authentication tokens: store in memory or EncryptedSharedPreferences, never plain SharedPreferences or files.
- Network: enforce HTTPS, certificate pinning for high-security apps, check CertificateException handling.
- Insecure deserialization: validate data class structure before use; avoid generic ObjectInputStream.
- Permissions: request only what is needed, at the point of use; handle denial gracefully.
- Flag any hardcoded secrets, API keys, or credentials — externalize them to BuildConfig or environment.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "performance-optimization",
            displayName = "Performance Optimization",
            description = "Identify performance bottlenecks and apply lazy loading, caching, and efficient data structures.",
            category = "Performance",
            tags = listOf("performance", "optimization", "memory", "caching", "lazy"),
            prompt = """
When optimizing performance:
- Profile before optimizing — identify actual bottlenecks with Android Profiler or JVM profiler, not assumptions.
- Use lazy { } for expensive one-time initializations; use sequences (asSequence()) for large collection pipelines.
- Avoid object allocations in hot paths (loops, RecyclerView.onBindViewHolder): reuse, pool, or use primitives.
- Cache expensive computations with @Volatile + double-checked locking, or Kotlin's lazy(LazyThreadSafetyMode.NONE) if single-threaded.
- For I/O: batch reads/writes; use buffered streams; consider memory-mapped files for large data.
- In Compose: use remember { } to memoize, derivedStateOf for derived computations, key() for stable lists.
- Database: add indexes for frequently queried columns; use transactions for bulk inserts.
- Prefer data structures by access pattern: ArrayDeque for LIFO/FIFO, HashMap for O(1) lookup, TreeMap for sorted iteration.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "code-review",
            displayName = "Code Review",
            description = "Thorough code review focusing on correctness, naming, edge cases, and maintainability.",
            category = "Quality",
            tags = listOf("review", "quality", "naming", "edge-cases", "best-practices"),
            prompt = """
When performing a code review check for:
- Correctness: null safety, index bounds, arithmetic overflow, resource leaks (streams/connections closed in finally/use).
- Naming: classes are nouns, functions are verbs, booleans prefixed with is/has/can; names reflect intent.
- Edge cases: empty collections, null/blank strings, concurrent access, network failures, empty results from APIs.
- Complexity: cyclomatic complexity > 10 is a smell — suggest extraction; max function length ~30 lines.
- Duplication: flag any block repeated 2+ times — extract to a function, extension, or utility class.
- Thread safety: shared mutable state accessed from multiple coroutines/threads must be synchronized or use atomic types.
- Testability: hard-coded dependencies, static calls, and global state make testing hard — flag and suggest injection.
- API design: public APIs should be hard to misuse; use value types/sealed classes over raw strings/ints for domain concepts.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "rest-api",
            displayName = "REST API Design",
            description = "REST endpoint design with proper status codes, DTOs, and error responses.",
            category = "Backend",
            tags = listOf("rest", "api", "http", "dto", "spring", "ktor"),
            prompt = """
When designing REST APIs:
- Resources are nouns, plural (/users, /orders); actions use HTTP verbs (GET, POST, PUT/PATCH, DELETE).
- Status codes: 200 OK, 201 Created (POST), 204 No Content (DELETE), 400 Bad Request, 401 Unauthorized, 403 Forbidden, 404 Not Found, 409 Conflict, 422 Unprocessable Entity, 500 Internal Server Error.
- Use DTOs (separate from domain objects) for request/response bodies; validate with @Valid / validation annotations.
- Return consistent error responses: { "code": "USER_NOT_FOUND", "message": "...", "details": [...] }.
- Use pagination for list endpoints: ?page=0&size=20, return total count in response body or Link headers.
- Versioning: prefix with /api/v1/; avoid breaking changes — add optional fields, never remove.
- Idempotency: PUT and DELETE must be idempotent; POST for creation should handle duplicate detection.
- Never expose internal IDs or stack traces in error responses.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "dependency-injection-hilt",
            displayName = "Hilt Dependency Injection",
            description = "Android DI with Hilt: modules, components, scoping, and ViewModel injection.",
            category = "Android",
            tags = listOf("hilt", "di", "android", "injection", "dagger"),
            prompt = """
When using Hilt for Android dependency injection:
- Annotate Application with @HiltAndroidApp; annotate Activities/Fragments/Services with @AndroidEntryPoint.
- Use @HiltViewModel for ViewModels — inject via by viewModels() in Activity/Fragment.
- Create @Module + @InstallIn(SingletonComponent::class) for app-scoped dependencies; use @Singleton.
- Use @InstallIn(ViewModelComponent::class) for ViewModel-scoped bindings with @ViewModelScoped.
- Bind interfaces to implementations with @Binds in abstract @Module classes; use @Provides for third-party types.
- Use @ActivityContext / @ApplicationContext qualifiers for Context injection.
- Never instantiate dependencies manually in classes that Hilt injects into — let Hilt provide them.
- Prefer constructor injection over field injection (@Inject lateinit var) for testability.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "spring-boot",
            displayName = "Spring Boot",
            description = "Spring Boot patterns: beans, repositories, services, controllers, and configuration.",
            category = "Backend",
            tags = listOf("spring", "spring-boot", "java", "kotlin", "backend"),
            prompt = """
When writing Spring Boot code:
- Annotate correctly: @RestController (REST endpoints), @Service (business logic), @Repository (data access), @Component (general beans).
- Inject dependencies via constructor injection, not @Autowired field injection — enables immutability and testability.
- Use @Transactional on service methods that span multiple DB operations; understand propagation and isolation levels.
- Return ResponseEntity<T> from controllers for full control over status codes and headers.
- Validate request bodies with @Valid and Bean Validation annotations (@NotNull, @Size, @Pattern).
- Use @ControllerAdvice + @ExceptionHandler for global error handling; return structured ProblemDetail or custom error DTOs.
- Configuration: use @ConfigurationProperties(prefix = "app") data classes over individual @Value fields.
- Repository: extend JpaRepository<Entity, ID>; use @Query for custom JPQL/native queries; avoid N+1 with @EntityGraph.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "refactor-dry",
            displayName = "Refactor & DRY",
            description = "Apply DRY, KISS, and extract method/class to reduce duplication and complexity.",
            category = "Refactoring",
            tags = listOf("refactoring", "dry", "kiss", "clean-code", "extract"),
            prompt = """
When refactoring for quality:
- DRY (Don't Repeat Yourself): any knowledge duplicated in 2+ places should be extracted to a function, extension, or constant.
- KISS: the simplest solution that works is correct; avoid speculative abstraction for hypothetical future requirements.
- Extract Method: if a function is > 20–30 lines or has a comment explaining a block — extract that block into a named function.
- Extract Class: if a class has more than one responsibility or > 200 lines — split it.
- Replace magic literals with named constants or enum entries; never use raw strings/ints for domain concepts.
- Remove dead code: unused functions, commented-out blocks, unreachable branches — delete, don't comment out.
- Flatten arrow code: invert conditions and return early; avoid deeply nested if/else/try blocks.
- Prefer Kotlin idioms: use let/also/apply/run for scoping, takeIf/takeUnless for conditional nullability, mapNotNull for filtering+transforming.
""".trimIndent(),
        ),
    )

    fun findById(id: String): SkillDescriptor? = all.firstOrNull { it.id == id }

    fun categories(): List<String> = all.map { it.category }.distinct().sorted()
}
