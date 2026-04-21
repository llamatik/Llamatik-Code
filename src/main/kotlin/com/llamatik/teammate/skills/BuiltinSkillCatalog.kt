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

        SkillDescriptor(
            id = "frontend-design",
            displayName = "Frontend Design",
            description = "Build polished frontend components and pages with strong UX, hierarchy, and implementation quality.",
            category = "Frontend",
            tags = listOf("frontend", "ui", "ux", "design", "react"),
            prompt = """
When building frontend UI:
- Produce real, working code — not placeholders or vague mockups.
- Prioritize visual hierarchy, spacing rhythm, alignment, and clear interaction states.
- Design for the user goal first: identify primary action, supporting actions, and low-priority elements.
- Use reusable components and consistent patterns for buttons, cards, inputs, dialogs, and empty states.
- Include hover, focus, disabled, loading, error, and success states where relevant.
- Ensure responsive behavior across mobile, tablet, and desktop breakpoints.
- Prefer accessible semantic HTML and keyboard-friendly interactions.
- Use tasteful defaults: concise copy, restrained styling, and clear affordances.
- Avoid clutter — simplify aggressively and remove decorative elements that do not improve usability.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "webapp-testing",
            displayName = "Web App Testing",
            description = "Test web apps systematically, covering happy paths, edge cases, regressions, and UX bugs.",
            category = "Testing",
            tags = listOf("testing", "web", "qa", "regression", "e2e"),
            prompt = """
When testing a web application:
- Start with core user journeys: sign in, navigation, CRUD flows, forms, search, filters, and checkout or save flows if applicable.
- Check happy path first, then edge cases: invalid input, empty states, timeouts, slow loading, offline behavior, and permission failures.
- Verify visual and UX details: loading states, disabled states, error messages, focus order, keyboard navigation, and mobile responsiveness.
- Look for regressions: broken links, stale data, duplicate submissions, lost form state, and inconsistent validation.
- Be explicit about repro steps, expected behavior, and actual behavior.
- When reporting issues, include severity, frequency, and likely impacted user flow.
- Prefer deterministic test data and stable selectors for automation.
- If writing automated tests, focus on user-observable behavior rather than implementation details.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "shadcn-ui",
            displayName = "shadcn/ui",
            description = "Build UI using shadcn/ui patterns, component composition, and Tailwind-friendly conventions.",
            category = "Frontend",
            tags = listOf("shadcn", "react", "tailwind", "ui", "components"),
            prompt = """
When writing shadcn/ui-based frontend code:
- Prefer composing existing shadcn/ui primitives before creating custom components from scratch.
- Keep components small, focused, and easy to restyle with className and variants.
- Use cn() or equivalent class merging utility for conditional styling.
- Separate layout structure from presentational components when possible.
- Prefer Dialog, Sheet, DropdownMenu, Popover, Tabs, Command, and Table patterns from shadcn/ui for consistency.
- Forms should use clear labels, descriptions, validation messages, and accessible input associations.
- Use Tailwind utility classes consistently; avoid deeply nested custom CSS unless necessary.
- Keep styling tokens consistent across spacing, radius, border, and typography.
- Support dark mode if the project already uses it.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "typescript-best-practices",
            displayName = "TypeScript Best Practices",
            description = "Write robust TypeScript with safe types, narrowing, utility types, and maintainable APIs.",
            category = "Frontend",
            tags = listOf("typescript", "ts", "types", "frontend", "javascript"),
            prompt = """
When writing TypeScript:
- Prefer explicit domain types over any, unknown, raw string unions scattered through the codebase, or loosely shaped objects.
- Use type narrowing with typeof, in, discriminated unions, and user-defined type guards.
- Prefer interfaces for extensible object contracts and type aliases for unions, mapped types, and utility compositions.
- Use readonly where mutation is not intended.
- Model async return types clearly: Promise<Result>, Promise<Data | null>, etc.
- Avoid non-null assertions (!) unless the invariant is guaranteed and documented.
- Use generics only when they improve type relationships; do not over-abstract.
- Keep public function signatures simple and hard to misuse.
- Prefer exhaustive handling for unions with a never branch in switch statements.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "nextjs-app-router",
            displayName = "Next.js App Router",
            description = "Build Next.js App Router features with server components, route handlers, and modern data flow.",
            category = "Frontend",
            tags = listOf("nextjs", "react", "app-router", "ssr", "web"),
            prompt = """
When writing Next.js App Router code:
- Default to Server Components; use "use client" only when client-side interactivity is required.
- Fetch data on the server whenever possible to reduce client bundle size.
- Keep route segments organized by feature and co-locate loading.tsx, error.tsx, and page.tsx when useful.
- Use Route Handlers for backend endpoints inside the app when appropriate.
- Handle loading, error, and empty states explicitly.
- Use Server Actions only when they simplify form or mutation workflows and fit the project architecture.
- Be careful with caching and revalidation; choose static, dynamic, or revalidated behavior intentionally.
- Keep metadata, layouts, and nested routes structured and predictable.
- Avoid pushing business logic into client components.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "playwright-e2e",
            displayName = "Playwright E2E",
            description = "Write reliable Playwright end-to-end tests with stable selectors and realistic user flows.",
            category = "Testing",
            tags = listOf("playwright", "e2e", "testing", "web", "automation"),
            prompt = """
When writing Playwright tests:
- Test user-visible flows, not implementation details.
- Prefer getByRole, getByLabel, getByText, and stable data-testid selectors over brittle CSS selectors.
- Keep each test independent and reset state between tests.
- Use fixtures for shared setup such as authentication, seeded data, or page objects.
- Assert visible outcomes the user would perceive: text, URL, enabled state, dialogs, toasts, and persisted changes.
- Avoid arbitrary waits; use Playwright waiting primitives for network, locators, and navigation.
- Cover happy path, validation errors, permission issues, and regression-prone flows.
- Keep tests deterministic by controlling test data and environment assumptions.
- Group related scenarios with clear names and minimal duplication.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "postgres-best-practices",
            displayName = "Postgres Best Practices",
            description = "Design and optimize PostgreSQL schemas and queries with indexing, concurrency, and security in mind.",
            category = "Backend",
            tags = listOf("postgres", "sql", "database", "performance", "backend"),
            prompt = """
When writing PostgreSQL code:
- Design schemas intentionally: correct data types, constraints, foreign keys, and clear naming.
- Add indexes based on actual query patterns, not guesswork.
- Avoid SELECT * in production queries; select only required columns.
- Be mindful of N+1 query patterns and repeated round trips.
- Use transactions for multi-step writes that must remain consistent.
- Consider concurrency, locking, and isolation when multiple writers may touch the same rows.
- Prefer prepared statements or parameterized queries to avoid injection risks.
- Use EXPLAIN / EXPLAIN ANALYZE for slow queries and optimize based on evidence.
- Model soft delete, audit columns, and uniqueness constraints explicitly when needed.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "github-actions-ci",
            displayName = "GitHub Actions CI",
            description = "Build maintainable GitHub Actions workflows for linting, testing, builds, and releases.",
            category = "DevOps",
            tags = listOf("github-actions", "ci", "cd", "automation", "yaml"),
            prompt = """
When writing GitHub Actions workflows:
- Keep workflows focused by responsibility: CI, release, dependency updates, deploy, etc.
- Trigger on the minimal correct set of events: pull_request, push, workflow_dispatch, release, or schedule.
- Use matrix builds for multiple OS, language, or runtime versions when useful.
- Cache dependencies carefully to reduce build time without hiding reproducibility issues.
- Fail fast on lint, typecheck, test, and build errors.
- Store secrets only in GitHub Secrets; never hardcode credentials or tokens.
- Pin major action versions and prefer official or widely trusted actions.
- Upload useful artifacts such as reports, screenshots, or built packages when they help debugging or release flow.
- Keep job names and step names human-readable.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "systematic-debugging",
            displayName = "Systematic Debugging",
            description = "Debug issues methodically using reproduction, isolation, instrumentation, and hypothesis testing.",
            category = "Quality",
            tags = listOf("debugging", "diagnostics", "bugs", "troubleshooting", "quality"),
            prompt = """
When debugging:
- First define the symptom precisely: what is happening, where, and under what conditions.
- Reproduce the issue consistently before attempting a fix.
- Narrow scope by isolating variables: input, environment, timing, data shape, dependency behavior, and recent changes.
- Form a small set of hypotheses and test them one at a time.
- Add targeted logs, assertions, breakpoints, or temporary instrumentation close to the suspected failure point.
- Distinguish root cause from downstream symptoms.
- Fix the underlying cause, then verify no adjacent regressions were introduced.
- After resolving, remove temporary debugging code and add tests to prevent recurrence.
- Document the minimal repro and the final root-cause explanation.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "accessibility-audit",
            displayName = "Accessibility Audit",
            description = "Review and improve accessibility across semantics, keyboard support, focus, and contrast.",
            category = "Frontend",
            tags = listOf("accessibility", "a11y", "frontend", "ui", "audit"),
            prompt = """
When reviewing accessibility:
- Use semantic HTML first; do not replace native controls unless necessary.
- Ensure all interactive elements are keyboard reachable and operable.
- Verify visible focus indicators and sensible focus order.
- Associate labels, descriptions, helper text, and errors correctly with form controls.
- Use correct roles and ARIA only when native HTML cannot express the interaction.
- Check contrast, text resizing behavior, and responsive readability.
- Ensure images, icons, and controls have appropriate accessible names.
- Handle dialogs, menus, and toasts with correct focus management and announcements where needed.
- Treat accessibility issues as product quality issues, not optional polish.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "api-security-review",
            displayName = "API Security Review",
            description = "Review API implementations for auth, authorization, validation, secrets, and abuse resistance.",
            category = "Security",
            tags = listOf("api", "security", "auth", "validation", "backend"),
            prompt = """
When reviewing API security:
- Validate and sanitize all external input at the boundary.
- Check authentication and authorization separately: being logged in is not the same as having access.
- Ensure sensitive operations require the correct permissions and ownership checks.
- Never trust client-supplied role, tenant, or pricing information without server verification.
- Use parameterized queries and safe serializers/deserializers.
- Avoid leaking internal errors, stack traces, SQL details, or identifiers in responses.
- Rate-limit abuse-prone endpoints such as login, password reset, search, and file upload.
- Store secrets and tokens securely; never commit them or log them.
- Review file uploads, webhooks, redirects, and callback URLs for common abuse paths.
""".trimIndent(),
        ),

        SkillDescriptor(
            id = "mcp-server-builder",
            displayName = "MCP Server Builder",
            description = "Design and implement Model Context Protocol servers, tools, and schemas cleanly.",
            category = "AI Tooling",
            tags = listOf("mcp", "tools", "ai", "protocol", "integration"),
            prompt = """
When building an MCP server:
- Define a narrow, clear tool surface area with strong descriptions and predictable inputs/outputs.
- Use structured schemas for tool arguments and results; avoid ambiguous free-form payloads where possible.
- Name tools after user intent, not implementation details.
- Validate all incoming arguments and return explicit errors for invalid inputs.
- Keep tools deterministic and side effects obvious.
- Separate protocol wiring from business logic so tools remain testable.
- Log requests and failures safely without leaking secrets or sensitive user data.
- Document each tool with purpose, required inputs, failure modes, and example usage.
- Prefer a few high-quality tools over many overlapping ones.
""".trimIndent(),
        ),
    )

    fun findById(id: String): SkillDescriptor? = all.firstOrNull { it.id == id }

    fun categories(): List<String> = all.map { it.category }.distinct().sorted()
}
