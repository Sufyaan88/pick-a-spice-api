# Pick a Spice API

Spring Boot backend for the family business catalogue. See [FEATURES.md](FEATURES.md) for scope and agreed behaviour.

## Run tests

Requirements: Java 21 and Docker running with Linux containers. Use the checked-in Maven Wrapper.

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
```

On macOS/Linux, use `./mvnw` instead. Both commands run integration tests; `verify` also packages and verifies the build. Integration test names end in `IntegrationTests` and are included by Maven Surefire's `*Tests` convention.

Testcontainers starts disposable PostgreSQL 18 containers and supplies their connection details automatically. No PostgreSQL installation, pre-created test database, or database environment variables are needed. The first run downloads dependencies and Docker images. Containers are removed after testing.

Category tests send real HTTP requests to an embedded server on a random port, verify responses, and query PostgreSQL to check committed results. They do not mock controllers, services, repositories, or authentication. Fixed test-only credentials exercise Spring Security's default HTTP Basic authentication; CSRF tokens come from its actual login page. The temporary authentication setup will change when the admin-security feature is built.

Common starting data is defined once in `src/test/java/com/pickaspice/api/category/CategoryFixtures.java`. For example, `givenCategoryId(EXISTING_CATEGORY)` creates a fresh category through the real API and returns its generated ID. Fixture maps and lists are immutable; tests copy them for variations. Boundary and invalid inputs stay visible in their tests. Database records are cleaned before and after every test, so mutations cannot affect later tests. Nothing is seeded automatically into the development database.

## PR test checks

`.github/workflows/pr-tests.yml` runs the `Maven verification` check when a PR is opened, updated, reopened, or marked ready for review. It uses Java 21 on an Ubuntu runner, checks Docker, and runs `sh ./mvnw --batch-mode --no-transfer-progress verify`. Testcontainers supplies disposable PostgreSQL; no database credentials or external database service are needed. Maven dependencies are cached and newer runs cancel older runs for the same PR. There are no branch or path filters that could accidentally skip the required check.

The YAML file alone does not block merging. The owner must complete these steps in GitHub:

1. Push the workflow and open or update a PR manually. Let `Maven verification` run once so GitHub can offer it as a required check.
2. Add or update branch protection or an active branch ruleset for the target branch. Require a pull request before merging and require the `Maven verification` status check from GitHub Actions.
3. Apply the rule to administrators too, or leave the ruleset bypass list empty, if failing checks must block the owner as well. Keep the check name stable when editing the workflow.

See GitHub's [required status checks documentation](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches#require-status-checks-before-merging). Repository settings and the first hosted run remain owner tasks. The agent only prepared and validated local files; it did not push, open a PR, run hosted actions, or change repository settings.

## Run the API

Provide a separate running PostgreSQL database for development. Set these environment variables in your shell or IDE, keeping credentials out of tracked files:

| Variable | Purpose |
| --- | --- |
| `DATABASE_URL` | JDBC URL; defaults to `jdbc:postgresql://localhost:5432/pick_a_spice` |
| `DATABASE_USERNAME` | Database user; no committed credential default |
| `DATABASE_PASSWORD` | Database password; no committed credential default |

```powershell
.\mvnw.cmd spring-boot:run
```

Flyway applies numbered SQL migrations from `src/main/resources/db/migration`. Hibernate validates the resulting schema. Add a new migration for later changes rather than editing a migration already applied to a shared database.

## Create category

`POST /api/admin/categories`

```json
{
  "name": "Herbal Oils",
  "description": "Natural oils",
  "active": true
}
```

Returns `201` with the generated numeric `id` and saved fields. The name is required, trimmed, limited to 100 characters, and unique regardless of capitalization. Description is optional and limited to 1,000 characters. Active defaults to true when omitted or null. Invalid input returns `400`; duplicate names return `409`.

## Update category

`PUT /api/admin/categories/{id}`

```json
{
  "name": "Herbal Oils",
  "description": "Updated description",
  "active": false
}
```

Returns `200` with the existing numeric `id` and replaced fields. Name follows the create rules and can remain unchanged. Active is required. Omitted or null description clears it. Invalid input returns `400`, a missing category returns `404`, and a duplicate name returns `409`. Rejected updates leave stored records unchanged.

Create and update share a request record, response record, and persistence/error handling. The request uses an update validation group to require active only for PUT.

## Read category

`GET /api/admin/categories/{id}`

Returns `200` with `id`, `name`, `description`, and `active` for the requested category, including inactive categories. Missing categories return `404`; non-numeric IDs return `400`. Reads leave stored data unchanged.

## List categories

`GET /api/admin/categories?page=0&size=20`

Returns active and inactive categories sorted by numeric ID ascending, one page at a time:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalCount": 0
}
```

Each item has the same fields as the read-category response. Page numbers start at zero. Size defaults to 20 and must be between 1 and 100. The offset (`page * size`) must not exceed 2,147,483,647. Invalid pagination, including larger offsets, returns `400`. Within that limit, empty catalogues and pages beyond available results return an empty items array; totalCount reports all categories. The response uses the reusable `PageResponse<T>` record.

## Delete category

`DELETE /api/admin/categories/{id}`

Permanently removes the requested category, active or inactive, and returns `204` with no body. Missing categories return `404`; invalid numeric IDs return `400`. Other categories remain unchanged. Use an update with `active: false` when the category should be hidden rather than removed.

All category endpoints require authentication. Write operations also require CSRF protection; GET does not require a CSRF token. Dedicated admin access rules and public browsing are not implemented yet; they remain separate features.
