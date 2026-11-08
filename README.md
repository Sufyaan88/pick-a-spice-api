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

Category tests send real HTTP requests to an embedded server on a random port, verify responses, and query PostgreSQL to check committed results. They do not mock controllers, services, repositories, or authentication. Each test creates a fresh admin through the real account-creation service and logs in over HTTP. The shared client gets CSRF tokens from `/api/auth/csrf` and keeps authenticated and anonymous cookie stores separate.

Product and login tests use the same real HTTP/PostgreSQL approach and share `ApiTestClient` for requests, real login, and CSRF handling. Immutable product starting data lives in `src/test/java/com/pickaspice/api/product/ProductFixtures.java`; category fixtures are reused for product setup. Tests clear products before categories and remove test users before and after each test. Idle expiry is tested with a one-minute timeout and a real wait; absolute expiry uses a three-second lifetime with ongoing real requests. Production defaults remain 30 minutes idle and one hour maximum. General suites use higher test-only traffic quotas; dedicated security suites override them with small quotas to verify actual rejection and recovery.

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
| `SESSION_COOKIE_SECURE` | Set `true` for HTTPS outside the `prod` profile; defaults to `false` for local HTTP. The `prod` profile sets secure cookies to `true`. |

```powershell
.\mvnw.cmd spring-boot:run
```

Flyway applies numbered SQL migrations from `src/main/resources/db/migration`. Hibernate validates the resulting schema. Add a new migration for later changes rather than editing a migration already applied to a shared database.

## Create an administrator account

With Java 21, a packaged application, and the development database environment variables described above, run this from an interactive terminal:

```powershell
.\mvnw.cmd verify
java -jar target/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=create-admin
```

The `create-admin` profile runs without an HTTP server. It prompts for an email, a password, and password confirmation, creates one active account, and exits. Password entry is hidden. Redirected input and IDE output panes without an interactive console are refused; there is no password argument or environment-variable fallback. Password buffers are cleared after success or failure. Errors terminate the command without creating an account.

The shared `users` table stores a generated numeric ID, email, password hash, active flag, and required role. V5 renames the original V4 `admin_accounts` table and assigns `ADMIN` to existing accounts, preserving their IDs, hashes, emails, active flags, and identity sequence. New users must have an explicit role; the setup command always assigns `ADMIN`. Customer accounts remain a future feature and will use the same table once a customer role and signup behaviour are implemented.

Emails are required, validated, limited to 254 characters, trimmed, and lowercased. PostgreSQL enforces case-insensitive uniqueness, including concurrent creation. Duplicate accounts are rejected without changing the existing account.

Passwords must contain 15–128 Unicode code points, cannot be entirely whitespace, and cannot match the local blocklist regardless of capitalization. Spaces and Unicode are accepted; passwords are not trimmed. No uppercase, digit, or symbol combination is required. The complete starter list is in [`blocked-passwords.txt`](src/main/resources/security/blocked-passwords.txt); it rejects predictable choices and is not a comprehensive breached-password database. No external service is called.

Spring Security's Argon2id encoder generates a fresh salt for each account, using 19 MiB of memory, two iterations, and one lane. Bouncy Castle supplies the implementation required by that encoder. Only the encoded hash is stored; the command never prints passwords or hashes. See [Spring's password-storage documentation](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html) and [OWASP's Argon2id guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#argon2id). Hashing cost should be measured on the eventual deployment hardware before release.

Created active admins can log in through the endpoints below. Normal application startup creates no admin accounts; there is no signup endpoint. Account commands require access to the configured database and must only be available to its trusted operator.

Admin integration tests use the real command/application logic and disposable PostgreSQL. They enter at the application boundary because this operation has no HTTP endpoint, verify committed rows and rejected operations, and clean accounts before and after each test. Migration tests upgrade a populated V4 schema, verify preserved records and generated IDs, and remove that isolated schema before and after each test. Shared immutable values are in `auth/AdminFixtures.java` under the test package.

## Maintain user accounts

Using the same packaged application and database environment variables, run one operation at a time:

```powershell
java -jar target/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=manage-users --operation=reset-password
java -jar target/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=manage-users --operation=disable
java -jar target/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=manage-users --operation=reactivate
java -jar target/api-0.0.1-SNAPSHOT.jar --spring.profiles.active=manage-users --operation=list --page=0 --size=20
```

These commands start no HTTP server and exit after the operation. Changes prompt for the email in an interactive terminal. Password reset also prompts twice with hidden input and applies the same password policy as creation. Passwords cannot be supplied as arguments or redirected input. Do not combine `manage-users` and `create-admin` profiles.

Reset clears login failures and lockout, revokes previous sessions, and preserves the account's active status. Disable prevents login and revokes previous sessions. Reactivate keeps the password, clears failures/lockout, and requires a fresh login; previous sessions never become valid again. Repeating the same active status does not revoke current sessions. Missing users are rejected without creating an account. Even the last admin can be disabled; the trusted operator can reactivate it through this command.

Listing is read-only and may be redirected. It shows ID, email, role, active status, and lock deadline in UTC, followed by page, size, and total count. It includes inactive accounts, sorts by ID ascending, defaults to page 0/size 20, and supports sizes 1–100 with nonnegative pages and an offset no greater than 2,147,483,647. It never prints passwords or hashes.

V7 adds a nonnegative session version to `users`, initially zero for existing rows. Each authenticated request checks the user's current active status, role, and version in PostgreSQL. After a reset or status change commits, stale sessions receive `401` on their next request, including writes; requests already underway are not cancelled. Fetch a fresh CSRF token before logging in again. The check works when the command runs in a separate process from the API. These operations have no HTTP endpoints.

## Admin login and sessions

| Endpoint | Request/result |
| --- | --- |
| `GET /api/auth/csrf` | Anonymous access; returns `token` and `headerName` for CSRF-protected requests |
| `POST /api/auth/login` | JSON `email` and `password`, plus CSRF header; returns `204` and establishes a session |
| `GET /api/auth/me` | Returns `200` with `id`, `email`, and `role`; logged-out/expired sessions return `401` |
| `POST /api/auth/logout` | Requires CSRF; invalidates the session, clears its cookie, and returns `204` |

On the login page, fetch the CSRF token, then send it in the named header (currently `X-CSRF-TOKEN`) with the login request. After login, fetch a fresh token before write requests: login changes the session ID and rotates CSRF protection. Keep cookies on subsequent requests; the session cookie is HttpOnly and SameSite=Lax. Deploy with `--spring.profiles.active=prod` and HTTPS: this profile requires the browser to send cookies only over HTTPS. It does not configure a TLS certificate or HTTPS listener; hosting must supply HTTPS. Local HTTP development keeps its existing defaults. Cross-origin frontend access is not enabled; CORS/deployment origins remain a later integration decision.

All catalogue maintenance endpoints require an authenticated `ADMIN` session. HTTP Basic authentication and Spring's generated login page are replaced. Sessions expire after 30 minutes without API requests or one hour after successful login, whichever comes first. Activity cannot extend the one-hour maximum. Expired sessions are rejected before writes; fetch fresh CSRF and log in again. There is no remember-me option. Sessions live in the application server's memory, so restarting the API ends them. They are separate from lockout state persisted in PostgreSQL.

Unknown email, wrong password, inactive account, and lockout return identical `401` problem-detail responses: `Unable to sign in with those credentials.` Malformed/missing/non-string credentials return `400`; login accepts JSON bodies up to 4 KiB, email up to 254 characters after trimming, and passwords up to 128 Unicode code points. Login does not reapply the creation blocklist or minimum-length policy to existing passwords. Missing/invalid CSRF returns `403` before authentication and does not count as a password failure. A failed login also clears any previous authentication in that browser session.

Five password failures for an existing active account in a rolling 15-minute window lock new logins for 15 minutes, including correct passwords. Locked attempts do not extend that deadline. Successful login clears failure history. Other accounts and existing sessions remain usable. V6 stores the lock deadline and failure times in PostgreSQL, serializing checks with a user-row lock; unknown emails create no records. See [FEATURES.md](FEATURES.md) for the complete policy.

Authentication traffic is also bounded before CSRF/session creation or password verification:

| Property | Default | Behaviour |
| --- | --- | --- |
| `app.auth.login-attempts-per-window` | `10` | Maximum login POSTs per client IP per window, including malformed and CSRF-rejected attempts |
| `app.auth.new-sessions-per-window` | `20` | Maximum new sessions per client IP per window, including sessions needed by rejected unsafe requests |
| `app.auth.traffic-window` | `1m` | Fixed window starting with the first admitted request; denied requests do not extend it |
| `app.auth.concurrent-logins` | `2` | Maximum simultaneous login requests per API process; excess requests are rejected without queuing for password/database work |
| `app.auth.max-tracked-sources` | `10000` | Maximum tracked client addresses in each of the login/session limiters; expired entries are reclaimed when capacity is needed |
| `app.auth.max-sessions` | `1000` | Maximum sessions stored by this API process, including anonymous sessions |
| `app.auth.maximum-session-age` | `1h` | Maximum time since successful login, independent of activity |

Per-IP throttling returns safe `429` problem details. Full login, tracking, or session capacity returns `503`. Both include `Retry-After`; callers should wait rather than repeatedly retry. Existing sessions can still be used or logged out when session storage is full. Limits reset when the process restarts and are not shared across replicas. No Redis, external service, or database migration is added. Values can be overridden through Spring configuration, for example `APP_AUTH_CONCURRENT_LOGINS`.

Addresses come from the direct network peer. Forwarded headers are ignored (`server.forward-headers-strategy=none`), preventing caller-supplied IP spoofing. Users behind a NAT share a quota. Behind a reverse proxy, all traffic shares that proxy's quota until trusted proxy handling is explicitly configured; deployment must revisit this with the actual proxy topology. These guards bound application resources and complement deployment-level traffic protection.

## JSON request validation

Numeric IDs in JSON bodies (`categoryId` and `parentId`) must be integer JSON numbers. Decimal numbers such as `1.9` or `1.0`, quoted numbers such as `"1"`, and empty strings return `400`; they are not converted into IDs. Active flags must use JSON `true` or `false`, rather than numbers or strings. Omitted and null values still follow each operation's rules below.

Field validation errors use request field names. Invalid image URLs appear under `errors.imageUrl`; alternative text without an image URL appears under `errors.imageAltText`. These rules apply to both POST and PUT, and rejected requests leave stored data unchanged.

## Create category

`POST /api/admin/categories`

```json
{
  "name": "Herbal Oils",
  "description": "Natural oils",
  "active": true
}
```

Returns `201` with the generated numeric `id` and saved fields, including nullable `parentId`. The name is required, trimmed, limited to 100 characters, and unique regardless of capitalization across all categories. Description is optional and limited to 1,000 characters. Active defaults to true when omitted or null. Invalid input returns `400`; duplicate names return `409`.

### Optional subcategories

Set `parentId` to an existing top-level category ID to create a subcategory. For example, create `Herbal Products` first, then create `Herbal Oils` with its ID as `parentId`. Omitted or null `parentId` creates a top-level category. Existing categories remain top-level after the V2 migration.

Only one level of subcategories is supported. A category cannot be its own parent, use a subcategory as its parent, or become a subcategory while it has children. Invalid relationships return `400`; missing parents return `404`. Category names remain globally unique, including across different parents.

Inactive categories can be parents. Parent and child active flags are maintained separately; changing a parent's active status does not rewrite its children. Hiding subcategories and their products beneath inactive parents will be implemented with public browsing.

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

Supply `parentId` to assign, keep, or change a parent. Omitted or null `parentId` removes the relationship and makes the category top-level. The same one-level rules apply as on creation; a category with children can still be renamed or deactivated while remaining top-level.

Create and update share a request record, response record, and persistence/error handling. The request uses an update validation group to require active only for PUT.

## Read category

`GET /api/admin/categories/{id}`

Returns `200` with `id`, `name`, `description`, `active`, and nullable `parentId` for the requested category, including inactive categories. Missing categories return `404`; non-numeric IDs return `400`. Reads leave stored data unchanged.

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

A category with subcategories cannot be deleted and returns `409`, even if the children are inactive. Move, detach, or delete its subcategories first. Deleting a subcategory leaves its parent unchanged. A PostgreSQL foreign key prevents orphaned children; transactional row locks protect hierarchy changes from concurrent requests.

All category endpoints require an authenticated `ADMIN` session. Write operations also require CSRF protection; GET does not require a CSRF token. Public browsing remains a separate feature.

## Create product

`POST /api/admin/products`

```json
{
  "name": "Black Seed Oil",
  "description": "100 ml bottle",
  "price": 45.00,
  "categoryId": 3,
  "active": true,
  "imageUrl": "https://images.example.test/black-seed-oil.jpg",
  "imageAltText": "Black Seed Oil in a 100 ml bottle"
}
```

Returns `201` with the generated numeric `id` and all the fields above. Name is required, trimmed, limited to 200 characters, and unique regardless of capitalization across all products. Description is optional and limited to 5,000 characters. Active defaults to true when omitted or null.

Price is required and expressed in South African rand. It must be greater than zero, have at most two decimal places, and fit within `NUMERIC(12,2)` (maximum `9999999999.99`). Java uses `BigDecimal`; requests with excess decimal places are rejected rather than rounded.

Category is required and can reference a top-level category or subcategory, including inactive categories or categories beneath inactive parents. A missing category returns `404`. Invalid input returns `400` problem-detail JSON; duplicate product names return `409`. Rejected requests do not save products or alter existing catalogue records.

One image is optional: provide an HTTPS URL of at most 2,048 characters, optionally accompanied by alternative text of at most 200 characters. Alternative text without an image URL is rejected. Omitted or null image fields are returned as null. The API stores metadata only; it does not fetch or upload image files. The example URL is a placeholder and must be replaced with a real externally hosted image.

Authentication and CSRF protection are required. Categories containing any products, including inactive products, cannot be deleted and return `409`; PostgreSQL enforces that relationship. Variants, multiple images, image uploads, slugs, and public browsing remain later features.

## Read product

`GET /api/admin/products/{id}`

Returns `200` with the saved product's `id`, `name`, `description`, `price`, `categoryId`, `active`, `imageUrl`, and `imageAltText`. Optional fields remain null when absent. Active and inactive products can be read, including products assigned to inactive categories. This is a private maintenance endpoint; public visibility rules remain deferred.

Missing products return `404` problem-detail JSON with `Product not found.` Invalid IDs, including values outside the numeric range, return `400`. Authentication is required; anonymous JSON requests return `401` without product data. GET requires no CSRF token and leaves stored products and categories unchanged.

## List products

`GET /api/admin/products?page=0&size=20`

Returns `200` with the same pagination envelope as category lists:

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalCount": 0
}
```

Items use the read-product response fields, including nullable description and image metadata. The private administration list includes active and inactive products across all categories and subcategories, including inactive categories and categories beneath inactive parents. Products are ordered by numeric ID ascending and paginated in PostgreSQL.

Page defaults to zero; size defaults to 20 and must be between 1 and 100. The offset (`page * size`) must not exceed 2,147,483,647. Invalid pagination returns `400` problem-detail JSON. Within that limit, an empty catalogue or a page beyond the available results returns an empty items array; totalCount counts all products.

Authentication is required; anonymous JSON requests return `401` without product data. GET requires no CSRF token and leaves stored data unchanged. Public browsing, filtering, and search remain separate features.

## Update product

`PUT /api/admin/products/{id}`

```json
{
  "name": "Black Seed Oil",
  "description": "200 ml bottle",
  "price": 80.50,
  "categoryId": 3,
  "active": true,
  "imageUrl": "https://images.example.test/black-seed-oil-200ml.jpg",
  "imageAltText": "Black Seed Oil in a 200 ml bottle"
}
```

Returns `200` with the existing numeric ID and replaced editable fields. Name, price, category, description, and image validation follow creation rules; keeping the product's own name is allowed. Active is required and must be a non-null boolean. Omitted or null description, image URL, or alternative text clears that field. Alternative text still requires an image URL in the same request.

Products can move to an existing top-level category or subcategory, including inactive categories and categories beneath inactive parents. Updates can activate or deactivate products. Missing products and categories return `404`; PUT does not create a missing product. Invalid input, malformed JSON, and invalid IDs return `400`; another product's name returns `409`. Rejected updates leave all stored product fields unchanged, including category, price, and image metadata.

Authentication and CSRF protection remain required. The update runs in one transaction and holds the destination category lock until commit, coordinating with category deletion. Images remain external URLs; updating metadata does not upload or delete image files.

## Delete product

`DELETE /api/admin/products/{id}`

Permanently removes the requested product, active or inactive, including its stored image metadata, and returns `204` with no response body. Other products and categories remain unchanged. Externally hosted image files are not deleted. Use an update with `active: false` to hide a product while retaining its record.

Missing products return `404` problem-detail JSON with `Product not found.` Invalid IDs, including values outside the numeric range, return `400`. Authentication and CSRF protection remain required: anonymous JSON requests with a valid CSRF token return `401`; signed-in requests without a token return `403`. Rejected requests leave existing data unchanged.

Updates and deletes lock the product row until their transaction commits. Concurrent deletes return one `204` and one `404`; a concurrent update either completes before deletion or returns `404`, leaving the product deleted. After its last product is deleted, a category can also be deleted if it has no subcategories.
