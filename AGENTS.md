# AGENTS.md

## Project overview

`pick-a-spice-api` is the backend for Pick a Spice, a public online product catalogue for the owner's family business. Its immediate purpose is to give the business a useful online presence and help potential customers discover its products. It is also a personal learning project, so changes should remain understandable, intentional, and practical for one part-time developer.

The first release is a public read-only catalogue with private catalogue maintenance, not an e-commerce system. Category maintenance supports creation, updates, authenticated reads by ID, paginated administration lists, and permanent deletion, with Flyway migrations and real HTTP/PostgreSQL integration tests. Dedicated admin security and public browsing remain future features. Read `FEATURES.md` before making product or architecture decisions.

Core stack:

- Java 21
- Spring Boot 4.1.0
- Maven 3.9.16 via the Maven Wrapper
- Spring MVC
- Spring Data JPA
- Spring Security
- PostgreSQL at runtime
- Lombok
- JUnit 5 and Spring Boot test support

## Repository layout

- `pom.xml`: dependencies, Java version, and build plugins
- `src/main/java/com/pickaspice/api/`: production Java code
- `src/main/resources/application.properties`: application configuration
- `src/test/java/com/pickaspice/api/`: tests
- `.mvn/wrapper/`: Maven Wrapper configuration
- `FEATURES.md`: product vision, current scope, roadmap, and open decisions
- `README.md`: local setup, test commands, and implemented endpoint behaviour
- `src/main/resources/db/migration/`: versioned Flyway SQL migrations
- `.github/workflows/pr-tests.yml`: PR verification workflow; the required check is named `Maven verification`
- `target/`: generated build output; do not edit or commit it

The root package is `com.pickaspice.api`. Keep new application classes under this package so Spring component scanning discovers them.

## Common commands

Use the checked-in Maven Wrapper instead of relying on a globally installed Maven version.

On Windows PowerShell:

```powershell
.\mvnw.cmd spring-boot:run
.\mvnw.cmd test
.\mvnw.cmd verify
.\mvnw.cmd clean package
```

On macOS or Linux:

```bash
./mvnw spring-boot:run
./mvnw test
./mvnw verify
./mvnw clean package
```

Run `test` while iterating and `verify` before handing off a change. Do not commit files generated under `target/`.

## Implementation conventions

- Favor code reuse and fewer purposeful types. Check existing code before adding a class or helper; extract shared logic when real duplication appears. Do not create generic frameworks or abstractions for hypothetical future needs.
- Use builders and factories only when construction complexity or creation rules justify them; do not generate them for every entity.
- Reuse `error.BusinessException`, `error.ErrorCode`, and `error.ApiExceptionHandler` rather than creating a separate exception class and handler for every feature or failure. Add codes and safe business-error messages to `ErrorCode` as needed; map codes to HTTP statuses in `ApiExceptionHandler`. Preserve underlying causes. Do not identify errors by comparing message strings or put HTTP statuses in business exceptions.
- Use clear, descriptive names and explain unfamiliar Java patterns briefly when needed. The owner works in C# professionally and is refreshing Java/Spring knowledge; avoid unnecessary ceremony and file proliferation.
- Prefer feature-oriented packages as the API grows, for example `spice`, `recipe`, or `auth`. Keep a feature's controller, service, repository, DTOs, and model close together rather than creating large global layer packages.
- Keep controllers thin. Controllers should handle HTTP concerns, services should hold business rules, and repositories should handle persistence.
- Keep HTTP request and response DTOs at the controller boundary. Services should accept application values and return domain results, without depending on those HTTP DTOs.
- Reuse `common.PageResponse<T>` for paginated HTTP results (`items`, `page`, `size`, `totalCount`). Map entities to response DTOs in the controller rather than exposing Spring Data page internals or JPA entities.
- Validate each layer's own concerns. Catch exceptions only to recover or translate them meaningfully: services translate recognized persistence failures into business exceptions, and web exception handlers translate business exceptions into HTTP responses. Let unrelated failures propagate.
- Do not expose JPA entities directly in API request or response bodies. Use explicit request and response DTOs.
- Use constructor injection. Lombok's `@RequiredArgsConstructor` is acceptable where it keeps the class clear.
- Validate incoming DTOs at the HTTP boundary and return deliberate status codes and error responses.
- Put cross-cutting configuration in focused configuration classes; do not add unrelated behavior to `ApiApplication`.
- Follow the formatting already used by the project and keep imports explicit. Avoid wildcard imports.
- Add comments only when they explain a non-obvious decision; prefer clear names and small methods.

## Product and scope guardrails

- Optimize first for a reliable, mobile-friendly public catalogue that helps real customers discover the family business and its products.
- Treat catalogue browsing as public and read-only in the initial release. Do not add checkout, carts, customer accounts, payments, or public write endpoints unless the active feature explicitly calls for them.
- The separate frontend application is the presentation layer; its framework is undecided (Nuxt, React, or Next.js). This repository owns the HTTP API, business rules, security, and access to structured data.
- The owner has about two hours on weekends. Prioritize small, complete API features that prepare the backend for frontend development. Defer hosting and deployment-provider decisions until needed.
- Keep the architecture a modular monolith. Do not introduce microservices, queues, Redis, a dedicated search engine, or other infrastructure without a demonstrated requirement.
- Prefer the simplest implementation that leaves a reasonable path to later e-commerce. Future possibilities are context, not current requirements.
- Cost matters. This is a part-time personal project serving a small business, so avoid dependencies or infrastructure that create unnecessary recurring costs or operational work.
- Learning matters, but production code should not become a technology showcase. Introduce a tool because it solves a current problem, and document the reasoning.
- Update `FEATURES.md` when a decision changes the product scope, roadmap, or an open product question.

## Persistence and configuration

- PostgreSQL is the configured runtime database dependency. Keep database credentials and other secrets out of Git.
- Supply environment-specific values through environment variables or untracked local configuration. Commit only safe defaults and documented placeholders.
- Make schema changes explicit. If a migration tool is introduced, use it consistently and do not rely on ad hoc manual database changes.
- Flyway is now the selected migration tool. Prefer additive, backward-compatible schema and API changes where practical; compatibility does not replace migrations.
- Avoid enabling destructive schema generation for shared or production environments.
- Store product image metadata and URLs in PostgreSQL, not image binaries. Actual images should live in an image CDN or object-storage service once a provider is selected.

## Security

Spring Security is present, so new endpoints are secured by default unless the security configuration intentionally says otherwise.

- Treat dedicated administrator authentication and authorization as a separate feature. Retain existing authentication and CSRF protections while building catalogue operations; do not interpret an `/admin` URL as role enforcement.
- Make public endpoints explicit and keep the permitted surface as small as possible.
- Never log credentials, access tokens, session identifiers, or sensitive request bodies.
- Do not disable CSRF, CORS protections, or authentication globally just to make a test pass. Configure the actual client and authentication model deliberately.
- Add authorization tests whenever endpoint access rules change.

## Testing expectations

- Structure behavioral tests with explicit `// Arrange`, `// Act`, and `// Assert` sections. Keep the operation under test out of assertion helpers and assertion expressions; helpers named `assert...` should only verify results.
- Reuse named immutable test fixtures for common starting data. Category fixtures live in `src/test/java/com/pickaspice/api/category/CategoryFixtures.java`; load fresh records through real HTTP setup helpers only when a test needs them. Keep edge-case inputs explicit and copy fixture maps before overriding values. Never share mutable entities or generated IDs across tests.
- Clean test data before and after each test, including failure paths. Fixture definitions remain read-only; only the fresh database records are changed by the operation under test. Do not globally seed tests that need an empty database.
- Development follows strict test-driven development using the red-green-refactor cycle:
  1. Write a test that describes the next behavior.
  2. Run it and confirm it fails for the intended reason, not because of broken setup or an unrelated error.
  3. Write the smallest amount of production code needed to make the test pass.
  4. Run the relevant tests and confirm they are green.
  5. Refactor when useful while keeping the tests green.
- Never write business logic before a relevant test has been observed failing for the correct reason.
- Prefer integration-style tests that exercise real production components working together. Test behavior through meaningful application boundaries instead of testing implementation details.
- Default to real HTTP requests against the complete application and disposable PostgreSQL, checking status codes, response bodies, and committed database results. Check that rejected requests do not save unintended data.
- Tests should survive internal refactoring: avoid method-call expectations or assertions about layer structure. For behaviour-preserving refactors, establish a green baseline and keep the existing behavioural tests green; do not manufacture a failing test for unchanged behaviour.
- Do not use mocks, stubs, or fakes merely to make collaborators return convenient values. Use actual production implementations and realistic infrastructure wherever practical.
- For persistence behavior, prefer a real PostgreSQL test instance, such as one managed by Testcontainers, rather than mocking repositories or substituting a database with different behavior.
- A focused unit test is an allowed exception when integration is genuinely impractical, excessively slow, nondeterministic, or requires an unavailable external system. Keep the exception narrow and explain the constraint when it is not obvious.
- When an external service cannot safely participate in automated tests, isolate that boundary and prefer a realistic local implementation, sandbox, or container before considering a mock.
- Mirror production packages under `src/test/java`.
- Name unit and slice tests `*Tests` and full integration tests `*IntegrationTests`.
- Add tests with each behavior change, covering the success case and meaningful validation, authorization, and failure cases.
- Use a real or containerized PostgreSQL instance for behavior that depends on PostgreSQL-specific semantics; do not assume an in-memory database is equivalent.
- Keep tests deterministic and independent. Do not depend on execution order or shared mutable data.

## Permission and Git boundaries

- Before each feature, give a very brief overview of the behavioral tests and minimum implementation. Wait for the owner to approve or adjust that plan.
- Approval covers the file changes, test runs, and refactoring needed to complete that feature. Do not ask for separate permission for each file or TDD step. Seek a revised plan only when scope expands materially or an unresolved decision blocks progress.
- Do not run any Git command, including read-only commands such as `git status`, `git diff`, or `git log`.
- Do not create, switch, merge, rebase, or delete branches. Do not stage or commit files, push changes, or create or update pull requests.
- The owner handles the complete Git workflow manually. Work is performed on owner-created feature branches, and the owner deletes each feature branch after its pull request is merged.

## Agent development workflow

Keep agreed workflow and architecture preferences in this file, and product or architecture decisions in `FEATURES.md`, so future sessions can continue without repeating the discussion. Distinguish planned changes from implemented behaviour.

Before changing code:

1. Read `pom.xml`, the affected production code, and its tests.
2. Confirm whether an existing pattern already solves the problem before introducing a new abstraction or dependency.
3. Agree the fields, types, required values, defaults, relationships, and constraints relevant to the operation. Settle inputs, expected results, and meaningful failure cases before implementing them.
4. Give a brief test and implementation plan for one feature, then wait for approval or adjustments.

Build one operation through the complete domain/repository/service/controller stack at a time, such as create category, then update category. Do not automatically build all CRUD operations for a table. An operation can involve multiple related tables in one transaction; each table does not need its own public endpoint or controller.

When changing code:

1. Write the behavioral test first and run it.
2. Confirm that the test is red for the expected reason before adding business logic. Report this briefly and continue without another approval request.
3. Implement only enough production code to make it green.
4. Run the relevant integration tests and confirm they pass.
5. Refactor if necessary, keeping the suite green throughout.
6. Complete the approved feature in one uninterrupted TDD workflow; keep the patch scoped to that behavior.
7. Update configuration and documentation when the approved behavior requires them.
8. Run `mvnw verify` before handoff when practical.
9. Briefly report what changed, the observed red and green test results, verification, and any remaining setup requirements. Wait for the next feature rather than starting roadmap work automatically.

Before handoff, review the actual changes for correctness, regressions, missing failure tests, secret exposure, and useful logging. Preserve the no-Git boundary: compare available file snapshots and inspect changed files without running Git; state when an exact staged or branch diff is unavailable. Report concrete findings before proposing fixes. Logging should help diagnose failures without recording credentials, tokens, request bodies, or adding repetitive messages at every layer.

Do not turn roadmap ideas in `FEATURES.md` into implementation requirements. Resolve choices about externally visible behavior, authentication rules, persistent data semantics, or new operational dependencies in the feature plan. Ask during implementation only if an unresolved choice blocks the approved work.

The GitHub Actions workflow in `.github/workflows/pr-tests.yml` is prepared to run Maven verification on PR creation and updates. Keep its `Maven verification` job/check name stable because the owner will configure it as required before merging. The first hosted run and branch protection/ruleset configuration remain pending owner tasks. Preparing workflow files is separate from the owner's Git workflow; the owner handles all pushes, PRs, and repository-setting changes. Do not claim merge blocking is active until the owner confirms the required check is configured.

## Communication

- Use the `caveman` skill when available. Keep communication short, plain, and strictly relevant to the task.
- Lead with the action or result. Include only decisions, blockers, essential explanations, and test outcomes.
- Avoid filler, repeated summaries, long preambles, and unnecessary technical detail. Use normal readable language, not caveman roleplay.
- Explain further when the owner asks or when a detail is necessary to make an informed decision. Brevity must not hide failures, uncertainty, or setup requirements.
- When guiding the owner through manual tasks such as GitHub settings, give one action or question at a time and wait for their answer before the next step.
