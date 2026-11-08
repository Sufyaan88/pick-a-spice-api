# Pick a Spice: Product and Feature Brief

## Purpose

Pick a Spice is an online product catalogue for the owner's family business. The business currently has a limited online presence, so the first goal is straightforward: make its products easier for potential customers to discover and browse.

This is also a personal, part-time learning project. The owner has professional experience with .NET and Blazor and is completing a fourth year of study. The project is an opportunity to deepen skills in Java, Spring Boot, frontend development, PostgreSQL, and eventually deployment and DevOps while delivering something useful to the family business. The frontend framework is undecided; React or Next.js may replace the earlier Nuxt choice.

Those goals should reinforce each other. The product should use sound engineering practices, but it should remain small enough to finish, operate, and improve in spare time.

The owner has about two hours on weekends. The immediate priority is to bring the API to a useful, tested state for frontend development through small, complete features. Hosting research and deployment-provider selection are deferred. Development remains test-first, with one brief test and implementation plan approved per feature before completing its red-green-refactor cycle.

## Product principles

1. **Serve the business first.** A small, deployed catalogue with accurate products is more valuable than an ambitious unfinished commerce platform.
2. **Start read-only.** Visitors can discover and view products; purchasing workflows are outside the initial release.
3. **Build in phases.** Add capabilities when the business needs them instead of assembling a speculative enterprise stack.
4. **Keep ownership clear.** The frontend renders the customer experience, Spring Boot owns backend behavior, PostgreSQL stores structured catalogue data, and a media service stores image files.
5. **Keep operating costs low.** Free or inexpensive services are desirable during early development, but provider limits and pricing must be rechecked before deployment.
6. **Learn deliberately.** Architecture and DevOps work should teach transferable skills without making the application harder to maintain.

## Users

### Catalogue visitor

A potential customer who wants to understand what the business sells, browse comfortably on a phone, and find enough information to take the next step with the business.

### Business owner or catalogue maintainer

The project owner is responsible for keeping product information correct and will update it when needed. A private admin area will provide catalogue-management tools. This administrative access is distinct from the public catalogue and does not create a reason for customer accounts.

### Developer and operator

The project owner, who builds the system part-time and needs deployment, diagnostics, and maintenance to remain affordable and manageable.

## Phase 1: read-only catalogue

### Confirmed scope

- A separate frontend consumes a Spring Boot JSON API; its framework is not yet selected.
- Visitors can access the catalogue without creating an account.
- Catalogue data is persisted in PostgreSQL.
- Product images are referenced by URL; image binaries are not stored in PostgreSQL.
- The experience should work well on mobile devices because customers may arrive through social links or mobile search.
- Public catalogue operations are read-only, while authenticated administration operations can maintain catalogue data.
- Catalogue cards show a product's name, primary image, and public price.
- Product detail pages provide richer information, including description, available variants, and relevant measurements such as weight or size.
- Products can have multiple variants with different sizes, weights, pack quantities, or prices.
- Variants are optional. A simple product has its own price and does not require a synthetic "Standard" variant. The first product operation creates a simple product with one optional externally hosted image.
- Products are grouped into categories. The catalogue covers much more than spices, including packaging products, herbal powders and oils, fragrances, religious items, incense, personal-care products, batteries, knives, scales, and other stock carried by the business.
- Inactive products are hidden from all public catalogue views.
- Inactive categories and their products are hidden from public browsing. This rule will be implemented with catalogue browsing.
- The interim ordering action is a pre-filled WhatsApp enquiry rather than a cart or online checkout.
- The same frontend application contains the public catalogue and a private admin area. There is no public customer registration or login.

### Proposed MVP capabilities

These are the smallest useful capabilities suggested by the product direction. The exact API contract still needs agreement before implementation.

- List available catalogue products.
- View the details of one product.
- Organize and filter products by category.
- Display one or more externally hosted product images.
- Open WhatsApp with a message containing the selected product and variant details.
- Allow an authenticated administrator to create and update products, variants, categories, images, prices, and active status.
- Return consistent API validation and error responses.
- Provide basic service health information for deployment monitoring without exposing sensitive internals.

### MVP non-goals

- Shopping cart and checkout
- Online payments
- Orders and fulfilment
- Customer registration or login; private administrator authentication is still required
- Reviews, wish lists, and recommendations
- Live inventory reservation
- Transactional customer email
- A dedicated search service
- Redis or distributed caching
- Microservices

These may be valuable later, but designing or implementing them now would increase delivery and operational risk without helping the first catalogue launch.

## Candidate domain model

The first model should remain generic because the business sells many kinds of products rather than only spices.

- `Product`: stable identifier, name, slug, description, category, price for products without variants, active status, and timestamps
- `ProductVariant` (optional): a customer-facing label such as `100 g`, `500 ml`, `Pack of 100`, or `Large`, plus price, active status, and an optional SKU
- Initially, `Product` holds one optional image URL and alternative text directly. A later multiple-image feature may introduce `ProductImage` with display order and a primary-image indicator.
- `Category`: generated numeric ID, name, active status, optional description, and optional parent category ID; supports one level of subcategories, with no URL name in the initial implementation
- Product specifications: optional structured details for information that varies by product type

A **slug** is a short, URL-friendly identifier derived from a readable name. For example, a product named `Black Seed Oil 100ml` could use the slug `black-seed-oil-100ml`, producing a page URL such as `/products/black-seed-oil-100ml`. Slugs avoid exposing database identifiers in customer-facing URLs and make links easier to read and share. They must be unique and should remain stable when possible.

Products without variants use their own price. For catalogue cards, a product with one active variant can show that variant's price. A product with several differently priced active variants can display the lowest price as `From R...`. The detail page presents the available variants and their exact prices. Simple-product price validation is agreed below; the transition between simple products and products with variants still needs agreement.

The exact representation of flexible specifications still needs design. Avoid adding spice-specific columns that do not make sense for bags, books, fragrances, knives, or other categories.

### Agreed category creation contract

- `POST /api/admin/categories` creates one category and returns `201` with its generated `id`, `name`, `description`, and `active` status.
- `name` is required, trimmed before validation and storage, and limited to 100 characters after trimming. Names are unique regardless of capitalization; surrounding whitespace does not distinguish names.
- `description` is optional, nullable, and limited to 1,000 characters.
- `active` defaults to `true` when omitted or null; explicit `false` creates an inactive category.
- Blank names and excessive lengths return `400` with field errors. Malformed JSON returns `400`. Duplicate names return `409`. Application errors use problem-detail JSON.
- PostgreSQL enforces name uniqueness, including concurrent requests. Flyway manages the schema; Hibernate validates it rather than creating or updating it.
- The controller maps HTTP request and response DTOs. The service translates duplicate-name persistence failures into a shared `BusinessException` with `ErrorCode.CATEGORY_NAME_EXISTS`, preserving the cause. The central `ApiExceptionHandler` maps that code to `409`; `ErrorCode` holds the safe business-error message without HTTP dependencies. Validation and malformed-JSON handling also use the central handler.
- Category creation requires a signed-in `ADMIN` session and CSRF protection, enforced by `SecurityConfiguration` as described in the admin-login contract below.
- Tests exercise a real HTTP server, real authentication, real application components, and disposable PostgreSQL through Testcontainers. No collaborators are mocked.

### Agreed category update contract

- `PUT /api/admin/categories/{id}` replaces the editable fields of an existing category and returns `200` with the saved category. It does not create missing categories or change the numeric ID.
- `name` uses the same trimming, length, and case-insensitive uniqueness rules as creation. Keeping the category's own name is allowed.
- `description` is optional, limited to 1,000 characters, and cleared when omitted or null.
- `active` must be supplied as a non-null boolean. Both activation and deactivation are supported.
- Invalid input returns `400`, a missing category returns `404`, and another category's name returns `409`. Rejected updates leave stored records unchanged; the update runs in one transaction.
- Create and update reuse `CategoryRequest`, `CategoryResponse`, and the shared persistence/error handling. A validation group requires `active` on updates while preserving the create default.
- Admin session authentication and CSRF protection remain in place. Public visibility of inactive categories will be implemented with browsing.

### Agreed category read-by-ID contract

- `GET /api/admin/categories/{id}` returns `200` with the saved category's `id`, `name`, `description`, and `active` status. Both active and inactive categories can be read through this authenticated maintenance endpoint.
- Missing categories return `404` with the shared category-not-found error. Non-numeric IDs return `400`. Anonymous JSON requests return `401` without category data.
- Reads leave stored data unchanged and do not require a CSRF token. An authenticated `ADMIN` session is required.
- The operation reuses `CategoryResponse`, `CategoryRepository`, and shared error handling. Read uses the existing category lookup; hierarchy writes use locked lookups as described in the subcategory contract below.

### Agreed category administration-list contract

- `GET /api/admin/categories?page=0&size=20` returns `200` with `items`, `page`, `size`, and `totalCount`. Items use the existing category response fields; totalCount counts all categories, not just the current page.
- Includes active and inactive categories, ordered by numeric ID ascending. Pagination is performed in PostgreSQL rather than loading the entire catalogue into memory.
- Pages start at zero and default to zero. Size defaults to 20 and must be between 1 and 100. Negative pages, invalid sizes, non-integer values, and values outside the integer range return `400` problem-detail JSON.
- The requested offset (`page * size`) must not exceed 2,147,483,647, JPA's maximum supported offset. Larger offsets return `400` problem-detail JSON using the shared `PAGE_OFFSET_TOO_LARGE` code, before querying the repository. Calculations use a long value so multiplying valid integer parameters cannot overflow silently.
- Within the supported offset range, an empty catalogue or a page beyond the available results returns an empty items array with the requested page information and the total category count.
- Authentication is required; anonymous JSON requests return `401`. GET requires no CSRF token and leaves data unchanged. The `ADMIN` role is enforced by the security filter chain.
- Reuses the category repository, service, and response mapping, plus `common.PageResponse<T>` for a stable reusable HTTP envelope. No schema migration is needed.

### Agreed category deletion contract

- `DELETE /api/admin/categories/{id}` permanently removes an existing category and returns `204` with an empty response body. Both active and inactive categories can be deleted.
- Missing categories return `404` using the existing category-not-found error. Non-numeric IDs and IDs outside the numeric range return `400`.
- Authentication and CSRF protection remain required. Anonymous requests with a valid CSRF token return `401`; signed-in requests without a CSRF token return `403`.
- Successful deletion removes the database row and leaves other categories unchanged. Rejected requests leave existing data unchanged. Use `active: false` to hide a category without permanently removing it.
- Reuses the category lookup, repository, shared error handling, and named test fixtures. Product relationships now also restrict deletion, as described in the product creation contract below.

### Agreed optional subcategory contract

- Category create and update accept nullable `parentId`. Category responses, including list items, include `parentId`; top-level categories return null. Lists remain flat and ordered by ID, with no implicit grouping or filtering.
- Omitted or null `parentId` creates a top-level category on POST and clears the parent on PUT. Existing categories become top-level through the additive V2 Flyway migration.
- Only one level is allowed. A category cannot be its own parent, use a subcategory as its parent, or move beneath another category while it has children. These failures return `400`; missing parents return `404`. Rejected operations leave all stored data unchanged.
- Subcategories can move between top-level parents, keep their current parent, or become top-level. Top-level categories with children can still be edited while remaining top-level.
- Category names remain case-insensitively unique across the whole catalogue, regardless of parent.
- Deleting a category with any subcategories returns `409`; children are never deleted or detached automatically. Deleting a subcategory leaves its parent unchanged.
- Inactive parents are allowed for private maintenance. Parent and child active flags are independent. Public browsing will hide subcategories and their products beneath inactive parents; that visibility behaviour remains deferred.
- PostgreSQL enforces the parent foreign key and rejects self-reference. The service validates hierarchy depth, locking affected category rows in ID order within each write transaction so concurrent requests cannot create extra levels or orphaned children. Parent IDs are stored directly on the entity, avoiding unnecessary parent loading for HTTP responses.
- Existing authentication and CSRF protections remain in place. Reuses the category stack, shared business errors, and real HTTP/PostgreSQL integration tests.

### Agreed product creation contract

- `POST /api/admin/products` returns `201` with generated numeric `id`, `name`, `description`, `price`, `categoryId`, `active`, `imageUrl`, and `imageAltText`.
- Name is required, trimmed before validation and storage, limited to 200 characters, and case-insensitively unique across all products. Description is optional, nullable, and limited to 5,000 characters.
- Price is required, positive, and expressed in South African rand, with at most two decimal places and ten integer digits. Use Java `BigDecimal` and PostgreSQL `NUMERIC(12,2)`. Reject excess precision rather than rounding requests.
- Category is required and must exist. Products can belong to either a top-level category or subcategory. Inactive categories and categories beneath inactive parents remain available for private maintenance; public visibility remains deferred.
- Active defaults to true when omitted or null. Explicit false creates an inactive product.
- One image is optional. Image URL must be a valid HTTPS URL of at most 2,048 characters; alternative text is optional and limited to 200 characters. Alternative text without an image URL is rejected. Metadata is stored directly on the product; image files remain external, and the API does not fetch or upload them.
- Invalid input and malformed JSON return `400` problem-detail JSON; a missing category returns `404`; duplicate product names return `409`. Rejected operations leave existing records unchanged and save no product.
- Categories containing any products, active or inactive, cannot be deleted and return `409`. Products are never deleted automatically. A foreign key enforces the relationship, and creation locks the selected category until commit to coordinate with deletion.
- An authenticated `ADMIN` session and CSRF protection are required.
- Creation uses the product entity/repository/service/controller stack, shared business errors, and an additive V3 Flyway migration. Optional variants, multiple images, image uploads, slugs, timestamps, and public browsing are deferred. Read by ID, administration listing, updates, and deletion are described below.

### Agreed product read-by-ID contract

- `GET /api/admin/products/{id}` returns `200` with the existing product response fields: `id`, `name`, `description`, `price`, `categoryId`, `active`, `imageUrl`, and `imageAltText`. Nullable fields remain null when absent.
- Authenticated maintenance reads include active and inactive products, including products in inactive categories. Public browsing and its visibility rules remain separate features.
- Missing products return `404` with the shared `PRODUCT_NOT_FOUND` business error and safe message `Product not found.` Non-numeric IDs and values outside the numeric range return `400`. Anonymous JSON requests return `401` without product data.
- GET requires no CSRF token and leaves stored products and categories unchanged. Reuses the product repository, service, controller response mapping, shared errors, real HTTP test client, and immutable fixtures. No schema migration or new production types are needed.

### Agreed product administration-list contract

- `GET /api/admin/products?page=0&size=20` returns `200` with `items`, `page`, `size`, and `totalCount`, using `common.PageResponse<T>`. Items contain the existing product response fields; totalCount counts all products across all pages.
- Includes active and inactive products from all categories and subcategories, including inactive categories and categories beneath inactive parents. Orders by numeric ID ascending and paginates in PostgreSQL. Filtering, search, and public visibility rules are separate features.
- Page defaults to zero and must be non-negative. Size defaults to 20 and must be between 1 and 100. Non-integer values and values outside the integer range return `400` problem-detail JSON.
- The requested offset (`page * size`) must not exceed 2,147,483,647, JPA's supported maximum. Larger offsets return `400` using the existing `PAGE_OFFSET_TOO_LARGE` business error, with calculations performed using a long value.
- Within that limit, empty catalogues and pages beyond available results return an empty items array with the requested page information and full total count.
- Authentication remains required; anonymous JSON requests return `401` without product data. GET requires no CSRF token and leaves products and categories unchanged.
- Reuses the product repository/service/controller, response mapping, shared pagination envelope and error code, real HTTP test client, and immutable product fixtures. No schema migration or new production types are needed.

### Agreed product update contract

- `PUT /api/admin/products/{id}` replaces the editable fields of an existing product and returns `200` using the existing product response. The numeric ID is preserved; missing products are never created.
- Name, price, category, description, and image fields follow creation rules. Keeping the product's own case-insensitive name is allowed. Active is required as a non-null boolean; activation and deactivation are supported.
- Omitted or null description, image URL, and alternative text clear those fields. Alternative text without an image URL in the request remains invalid.
- Category can remain unchanged or move to another existing top-level category or subcategory, including inactive categories and categories beneath inactive parents.
- Invalid input, malformed JSON, and invalid IDs return `400`. Missing products or categories return `404`; another product's name returns `409`. Rejected updates leave stored records unchanged, including category, price, active status, and image metadata.
- Updates run in one transaction and acquire the destination category lock before changing the product, coordinating with category deletion. Shared save handling translates recognized duplicate-name failures while preserving their causes. Images remain external metadata; no files are fetched, uploaded, or deleted.
- Authentication and CSRF protection remain required. Reuses the product stack, shared business errors, response mapping, and real HTTP/PostgreSQL tests. A request validation group requires active for PUT while preserving the POST default. No schema migration or new production classes are needed.

### Agreed product deletion contract

- `DELETE /api/admin/products/{id}` permanently removes an existing active or inactive product and its image metadata, returning `204` with an empty response body. Externally hosted image files are not deleted.
- Missing products return `404` using the existing `PRODUCT_NOT_FOUND` business error. Non-numeric IDs and values outside the numeric range return `400` problem-detail JSON.
- Authentication and CSRF protection remain required. Anonymous JSON requests with a valid CSRF token return `401`; signed-in requests without a token return `403`.
- Successful deletion removes the database row and leaves other products and categories unchanged. Rejected requests leave existing data unchanged. Use `active: false` when hiding a product should retain its record.
- Once a category has no products or subcategories, it can be deleted through the existing category endpoint. Product deletion does not delete categories automatically.
- Updates and deletes lock the product row for their transaction. Concurrent deletes return one `204` and one `404`. A concurrent update either completes before deletion or returns `404`; it cannot recreate the deleted product.
- Reuses the product stack, shared errors, real HTTP test client, and immutable product/category fixtures. Tests create fresh records through HTTP and clean products before categories before and after each test in disposable PostgreSQL. No schema migration or new production classes are needed.

### JSON type and validation-error rules

- Numeric IDs in JSON request bodies must be integer numbers. Reject fractional/decimal tokens, quoted numbers, and empty strings with `400` before DTO conversion can truncate them or treat them as null. This applies to `categoryId` and optional `parentId` on POST and PUT; valid integer IDs and explicitly permitted nulls retain their existing behaviour.
- Active flags accept JSON booleans. Numbers, quoted booleans, and empty strings return `400`. Creation still defaults omitted/null active to true; updates still require an explicit non-null boolean. The rules apply to both categories and products.
- A focused `JsonConfiguration` customizes the existing Jackson mapper's integer and boolean coercion rules. URL/query parameter conversion and the existing decimal price handling remain separate.
- Product image validation reports violations against `imageUrl` and `imageAltText`, matching the input fields. `ValidProductImage` keeps the image and cross-field checks together, with its validator nested beside the annotation; `ApiExceptionHandler` continues mapping field errors without product-specific aliases.
- Real HTTP/PostgreSQL regression tests cover rejected input types and unchanged data on POST and PUT, plus image-error field names. No schema change or new dependency is required.

### Agreed administrator account provisioning (implemented)

- The owner creates accounts through an interactive terminal command using the `create-admin` Spring profile. There is no signup endpoint, invitation system, or automatic account creation on ordinary startup. The command starts no HTTP server and exits after creating one account.
- The shared `users` table stores a generated numeric ID, required unique email, password hash, active flag, and required role. V4 originally created `admin_accounts`; V5 renames it to `users`, assigns `ADMIN` to existing accounts, and preserves their IDs, emails, hashes, active flags, and identity sequence. Existing migrations remain unchanged.
- New accounts from the setup command are active and explicitly receive `ADMIN`. There is no database role default, so future creation paths cannot silently inherit admin status. A string-mapped domain enum and database constraint currently permit `ADMIN`; customer accounts will extend those allowed values through a later migration and share this table. Customer signup remains out of scope. Active admins can log in through the session endpoints below.
- Emails are trimmed, lowercased, syntactically validated, and limited to 254 characters. PostgreSQL enforces case-insensitive uniqueness, including concurrent requests. Duplicate creation preserves the original account.
- Passwords require 15–128 Unicode code points, allow spaces and Unicode without trimming, and cannot be entirely whitespace. No mandatory uppercase, digit, or symbol combinations. The agreed 16-entry starter blocklist lives in `src/main/resources/security/blocked-passwords.txt` and is checked case-insensitively. This is a local predictable-password list, not a comprehensive breached-password check; no external service or email delivery is introduced.
- Passwords are entered twice using the terminal's hidden password prompt. Mismatched confirmation, cancelled input, invalid email/password, and duplicate email create no account. Password arrays are cleared after processing; passwords and hashes are never printed. Noninteractive input is refused without a fallback through arguments or environment variables.
- Spring Security's Argon2id encoder uses a fresh per-password salt, 19 MiB memory, two iterations, and one lane, with Bouncy Castle as its required implementation dependency. The database stores the encoded hash only. Tune hashing cost against deployment hardware before release.
- Real PostgreSQL integration tests exercise the application/command boundary, rather than introducing an HTTP endpoint for terminal setup. They verify committed results, invalid input, blocklist entries, uniqueness races, independent salts, confirmation, buffer cleanup, refusal of redirected input, and explicit admin-role assignment. Missing and unknown roles are rejected by PostgreSQL. V4-to-V5 upgrade tests preserve active/inactive accounts and verify subsequent generated IDs. Fixtures are immutable; accounts and isolated upgrade schemas are cleaned before and after every test.
- Catalogue maintenance now uses these database-backed admin accounts and retains CSRF protection. The generated default user, HTTP Basic authentication, and generated login page are replaced.

### Agreed admin login/session contract (implemented)

- `GET /api/auth/csrf` is anonymous and returns `token` and `headerName`. The frontend fetches it on login-page load and sends the token with login/write requests. Responses use Spring Security's no-store headers.
- `POST /api/auth/login` accepts JSON string fields `email` and `password` with a valid CSRF header. Email is trimmed/lowercased; an active `ADMIN` account and matching Argon2id hash are required. Success returns `204` without a body and establishes the session. HTTP Basic and generated form login are disabled.
- `GET /api/auth/me` returns `200` with `id`, `email`, and `role`, mapped from a safe identity principal that contains no hash or credentials. Anonymous/expired sessions return `401`.
- `POST /api/auth/logout` requires CSRF, invalidates the server session, deletes its cookie, and returns `204`. Replaying the old session cannot authenticate. Anonymous logout with valid CSRF is idempotent.
- Login changes the session ID to prevent fixation and clears the old CSRF token through Spring Security's session strategies. Fetch a fresh CSRF token after login and after logout before subsequent write/login requests. Failed login clears previous authentication for that browser session.
- Malformed JSON, missing/non-string/blank credentials, an email longer than 254 trimmed characters, a password exceeding 128 Unicode code points, or a body over 4 KiB returns `400` with safe problem details. Existing passwords are verified without reapplying account-creation minimum length/blocklist rules. Missing/invalid CSRF returns `403` before password checks, without recording failures.
- Credential JSON is parsed inside the authentication filter, avoiding MVC request/body logging. Credential-bearing parser exception messages are discarded at this boundary; safe error responses contain no submitted values. Password request and CSRF response string representations redact secrets. Infrastructure errors still propagate.
- V6 adds `users.locked_until` and `login_failures` with a cascading user foreign key. Each login locks its user row and checks a rolling 15-minute window. Failure results commit before the authentication manager rejects them. History is bounded to four recent failures per account; the fifth starts the 15-minute lock and clears history. Expired records are pruned on subsequent checks, so no background job is needed.
- Unknown emails create no database rows, and inactive accounts cannot log in. Password verification also performs Argon2 work for unknown accounts using a dummy hash; no plaintext passwords or authentication/CSRF tokens are stored in PostgreSQL.
- Sessions are local to the application server and end on restart; lockouts persist in PostgreSQL. Cookie defaults are HttpOnly and SameSite=Lax. Local HTTP uses `SESSION_COOKIE_SECURE=false`; the `prod` profile sets secure cookies to true. Other HTTPS environments can set the environment variable to true. Hosting must provide HTTPS; a secure cookie flag does not enable TLS. CORS remains closed until frontend/deployment origins are agreed.
- Real HTTP/PostgreSQL tests cover login, normalized email, generic failures, CSRF, session rotation/reuse, logout replay, lockout duration/expiry, concurrent attempts, unaffected existing sessions/other accounts, and credential-safe logging. A one-minute test session timeout plus a real wait checks expiry without replacing the session manager. Catalogue tests use the same real login flow and fresh accounts.

- Login uses email/password and server-side browser sessions with an HttpOnly cookie, rather than JWT. Retain CSRF protection and require secure transport/cookies in deployment. All maintainers receive the same admin-level catalogue access; no public customer registration.
- Sessions expire after 30 minutes of inactivity or one hour after successful login, whichever comes first. The identity principal carries the login time internally; activity does not renew it and it is not exposed by `/me`. Expiry is checked before the database/session-version check or any endpoint write, returning `401` on the next authenticated request. Fresh login establishes a fresh lifetime. Closing a tab does not deliberately end a session; explicit logout does. No remember-me feature initially.
- Failed login uses one safe message for unknown email, wrong password, or disabled accounts.
- Five failed logins for the same account within 15 minutes trigger a 15-minute block on new logins, including correct credentials. Existing sessions and other accounts remain unaffected. This deliberately accepts the risk that someone knowing an email can trigger a temporary lockout. The window is rolling: times at or before 15 minutes ago are discarded. Success clears history, and attempts during lockout do not extend the deadline.

### Agreed user maintenance contract (implemented)

- The trusted operator uses the `manage-users` profile with exactly one `--operation`: `reset-password`, `disable`, `reactivate`, or `list`. It uses the configured database, starts no HTTP server, and exits after completion. There are no maintenance HTTP endpoints, recovery emails, invitations, or additional owner role. Access to the terminal and database credentials is the operator boundary. Normal API startup performs none of these operations; combining account-command profiles is rejected.
- Changes require an interactive console and prompt for the normalized, validated user email. Missing users are rejected without upserting. Reset prompts twice for a hidden password, reuses creation's Argon2id and password policy, and clears password buffers on success, rejection, or cancellation. Arguments and redirected input cannot supply passwords. Unsupported or ambiguous command options fail with safe messages.
- Reset replaces only the password hash, clears login failures and lockout, and revokes all previous sessions. It retains ID, email, role, and active status: a disabled user stays disabled. Other users and their sessions are unaffected.
- Disable retains the password and account record, prevents login, and revokes previous sessions. Reactivate retains the password, clears lockout/history, and requires fresh login. Even a session unused throughout disable/reactivate remains revoked. Repeating an unchanged status does not increment the session version or revoke healthy sessions; repeating reactivate still clears lockout/history. The last admin may be disabled and recovered through the operator command.
- Listing is read-only, includes active/inactive/locked users, and sorts by ID ascending. It prints ID, email, role, active flag, and lock deadline in UTC, plus page, size, and total count. No password, hash, or internal session version is printed. Output may be redirected. Pages start at zero/default zero; size defaults to 20 and accepts 1–100; `page * size` must not exceed 2,147,483,647. Invalid, repeated, or out-of-range pagination is rejected; empty and beyond-end pages are valid within the offset bound.
- Additive V7 creates `users.session_version`, a required nonnegative bigint defaulting to zero, preserving existing account data. It is an explicit authentication counter, not a JPA optimistic-lock version: ordinary login/lockout updates must not revoke sessions. Reset and actual active-state transitions increment it transactionally. Login and maintenance lock the same user row, so a concurrent login with the old password cannot survive a completed reset.
- The safe session principal captures the version at login. Every authenticated request queries PostgreSQL and requires the user to exist, be active, and retain the captured role/version. A stale session is invalidated and receives `401` before CSRF or endpoint execution, including writes. Revocation takes effect after commit on the next request across API/command processes; already-running requests are not cancelled. This adds one database lookup per authenticated request and needs no shared session store. The HTTP current-user response remains only ID, email, and role. Fetch a new CSRF token before logging in again after revocation.
- Real HTTP/PostgreSQL tests cover all-session revocation, rejected writes, fresh login, unaffected users, disabled resets, lockout recovery, repeated status changes, concurrent login/reset, deleted users, command input cleanup, safe paginated output, and rejected arguments. Tests use the production command/service boundary for terminal operations and real sessions to verify their effects.

### Authentication resource protection (implemented)

- The security review found that unknown/locked login requests could still consume Argon2/database resources, and anonymous CSRF requests could accumulate server sessions. Account lockout controls password guesses but does not replace traffic admission limits.
- Login POSTs are limited to 10 per direct client IP per one-minute fixed window, including malformed or CSRF-rejected requests. At most two login requests execute concurrently per API process; excess requests are rejected before password/database work rather than queued. Password checks retain dummy-hash work for unknown accounts and the existing transactional rolling account lockout.
- Actual new-session creation is limited to 20 per direct client IP per one-minute fixed window, including sessions created by rejected unsafe requests on other endpoints. Existing sessions are reused without spending new-session quota. Creation is serialized around the container's capacity check; at most 1,000 sessions are stored per API process. Full storage does not prevent existing sessions from being used or logged out.
- Each of the two limiters tracks at most 10,000 source addresses. Expired records are reclaimed when the map is full; a full map rejects new sources rather than growing unbounded. Time windows use monotonic elapsed time and begin with the first admitted request; rejected requests do not extend them. Limits are in process memory, reset on restart, and are not distributed across replicas. No new dependency, service, or schema migration is needed.
- Per-IP limits return `429`; full concurrency, source-tracking, or session capacity returns `503`. Responses contain safe problem details and `Retry-After`. Requests rejected before authentication do not record password failures or clear an existing authentication. No credentials, tokens, cookies, or submitted values appear in rejection messages.
- Direct peer addresses are used and forwarded headers are explicitly ignored to prevent address spoofing. NAT users share limits. Reverse-proxy deployment must deliberately configure trusted address handling; until then all users behind the proxy share its quota. This complements deployment-level traffic protection, without promising protection from all denial-of-service attacks.
- Limits and the one-hour maximum session age are positive configurable `app.auth.*` properties, documented with defaults in `README.md`. Production uses the `prod` profile for secure cookies and requires externally configured HTTPS. Local HTTP remains supported.
- Dedicated real HTTP/PostgreSQL tests use small quotas and real elapsed time to cover rejection/recovery, unknown accounts, existing authentication, spoofed headers, unsafe-request session creation, full session storage, concurrent login blocked by a real PostgreSQL row lock, absolute expiry despite activity, and secure production cookies. Existing catalogue/lockout tests use higher test-only quotas so their own operations remain independently testable.

## System context

The intended design is a decoupled three-tier application with four deployable concerns:

```text
Customer browser
      |
      v
Frontend app  ---- image requests ----> Image CDN / object storage
      |
      | HTTPS/JSON
      v
Spring Boot API
      |
      | JDBC/TLS
      v
PostgreSQL
```

- **Presentation:** The frontend owns pages, rendering, responsive behavior, and browser interaction.
- **Application:** Spring Boot owns the public API, business rules, validation, security boundaries, and persistence access.
- **Structured data:** PostgreSQL stores catalogue records and image references.
- **Media:** An image CDN or object store holds the actual image files and serves them directly to browsers.

The frontend application will have public routes such as `/catalogue` and `/products/{slug}`, plus private routes under `/admin`. The admin login does not need to be advertised in the public navigation. Spring Security must enforce access to administrative API operations; a hidden frontend route is not a security control.

The backend should begin as a modular monolith. Separate hosting services do not imply microservices.

## Technology direction

### Selected

- Frontend in a separate project, with framework selection deferred
- Java 21 and Spring Boot for this API
- PostgreSQL as the relational database
- Spring MVC, Spring Data JPA, and Spring Security
- Flyway for versioned SQL schema migrations
- External image storage rather than database binary storage
- One frontend application for both the anonymous public catalogue and authenticated admin area
- No signup or account functionality for public catalogue visitors
- A shared `users` table with an explicit role; owner-managed admin accounts provisioned through a terminal command with Argon2id password hashing
- Server-side session cookies for private admin login; no JWT or remember-me

### Not yet selected

- Frontend framework: Nuxt, React, or Next.js; decide when frontend work begins
- Production hosts for the frontend and API
- Managed PostgreSQL provider
- Image CDN or object-storage provider
- Frontend integration and deployment origins for private admin login; the HTTP contract is implemented
- Deployment automation and topology; a GitHub Actions PR test workflow is prepared separately

Hosting and deployment-provider research is outside the current API-development focus. Provider pricing, free tiers, inactivity policies, regional availability, and card requirements change over time. Research them at the time of selection; the earlier discussion of services such as Neon, Supabase, Cloudinary, Cloudflare R2, Render, or Vercel is background, not a binding decision.

### Development status and pending work

- Automated test setup uses named immutable category and product fixtures and real HTTP setup helpers. `ApiTestClient` shares real requests, login, and CSRF handling across suites. Each test creates fresh records as needed and cleans products before categories before and after execution; boundary and invalid-input scenarios remain explicit. These fixtures are test-only, not development seed data or customer accounts.
- Implemented: create category, update category, authenticated read by ID, paginated administration list, permanent deletion, field validation, PostgreSQL uniqueness, Flyway migrations, and real HTTP/PostgreSQL tests with explicit Arrange/Act/Assert sections. Request/response mapping is at the controller boundary; persistence failures are translated in the service.
- Verification on 2026-10-31: all 273 tests passed (118 category integration tests, 154 product integration tests, and the startup test) and Maven `verify` succeeded. Permanent validation regressions first reproduced silently accepted ID/boolean types and image errors under non-request field names. Green coverage now rejects decimal/quoted/empty IDs and numeric/quoted/empty active flags on POST and PUT without changing stored data, and checks image errors against `imageUrl` and `imageAltText`. Existing null/default rules, CRUD, pagination, concurrency, security, category deletion protection, and migration tests remained green. Tests reuse immutable fixtures, create fresh records through real HTTP, and clean disposable PostgreSQL before and after each test.
- Initial admin-provisioning verification on 2026-11-08: Maven `clean verify` passed all 319 tests, including 46 new admin account/command integration cases. Tests were observed red before implementation and green afterwards. A packaged-JAR check in a real terminal against a separate disposable PostgreSQL database confirmed hidden password/confirmation entry, normalized email, an active account with an Argon2id hash, and successful process shutdown. Redirected input returned exit code 1 without adding another account; the smoke-test container was removed afterwards. These are completed local results, not a guarantee about later changes. No real administrator accounts were created, and API login remains unchanged.
- Shared-user verification on 2026-11-08 after the shared-user adjustment: Maven `clean verify` passed all 326 tests (51 admin account/command cases, two user migration cases, 118 category cases, 154 product cases, and the startup test). The new shared-table and populated-V4 upgrade tests first failed because `users` was missing, then passed after V5 and the entity mapping changes. Coverage preserves active/inactive account data and ID generation, confirms the command assigns `ADMIN`, and rejects omitted, null, and unknown roles. Existing catalogue behavior remains green. Only disposable PostgreSQL was used; a real database will receive V5 on its next application startup.
- Admin-login verification on 2026-11-08: Maven `clean verify` passed all 351 tests (51 account-creation, 24 login/session, one secure-cookie, two user migration, 118 category, 154 product, and the startup test). Initial HTTP tests were red because the anonymous CSRF endpoint was still protected by default security. Green coverage includes real session expiry, logout/session replay, CSRF, generic login failures, rolling lockout persistence/concurrency, secure-cookie configuration, invalid/oversized/trailing JSON, and absence of passwords/tokens/session IDs in captured logs. Catalogue tests sign in through the actual database-backed login flow. Only disposable PostgreSQL was used.
- User-maintenance verification on 2026-11-08: Maven `clean verify` passed all 393 tests with zero failures, errors, or skips, including 24 maintenance and 18 listing cases. Reset, status-change, and listing tests were observed red before implementation, then green. Existing login, catalogue, and migration coverage remains green; upgrade tests verify existing rows receive session version zero. Packaged-JAR commands were checked in real interactive terminals against a separate disposable PostgreSQL database: creation, hidden reset/confirmation, disable, reactivate, and redirected safe listing all exited successfully. A separate running API returned login `204`, old-session `401` after the command reset, and fresh-password login `204`, confirming revocation across processes. The temporary API was stopped and the database container removed. No real accounts were created or changed. V7 applies to the configured database on its next startup.
- Latest verification on 2026-11-08 after authentication resource protection: Maven `clean verify` passed all 401 tests with zero failures, errors, or skips. Eight focused tests first failed for missing traffic limits, session capacity, absolute expiry, and secure production-cookie defaults, then all passed. A further direct expired-write regression was added before full verification. Coverage includes real elapsed-time recovery, existing-session reuse, ignored spoofed addresses, concurrent login admission while a real PostgreSQL row lock is held, capacity recovery through logout, expiry despite activity, and fresh login after expiry. All existing login, account maintenance, migration, category, and product suites remain green. Dedicated quota tests retain real HTTP/PostgreSQL; no mocks, new infrastructure, or schema changes were introduced. Reviewed the tracked diff and new files; no further blocking findings were identified. Production profile activation, HTTPS, and trusted reverse-proxy configuration remain deployment responsibilities.
- Implemented: shared `BusinessException`, `ErrorCode`, and central `ApiExceptionHandler` replace category-specific exception and handler classes. Existing response formats are preserved; codes are internal and are not added to response bodies.
- Implemented: owner-only interactive admin creation; V4–V7 migrations; shared `users` with explicit `ADMIN` assignment; Argon2id hashing and local password policy; JSON login, logout, current-user and CSRF endpoints; admin-only maintenance; 30-minute idle/one-hour maximum sessions; persistent account lockouts; operator password reset, disable/reactivate, paginated user listing, and account-wide session revocation; bounded authentication/session traffic and secure production cookies. Public browsing and inactive-category visibility are not implemented yet.
- Prepared: `.github/workflows/pr-tests.yml` runs Java 21, Docker-backed PostgreSQL tests, and Maven verification on PR creation and updates, using the stable check name `Maven verification`. Workflow syntax was checked locally with actionlint. The first GitHub-hosted run is not yet observed. The owner must push/open the PR and configure the check as required in branch protection or an active ruleset to block merging on failure; repository settings have not been changed.
- Future catalogue operations will be agreed and built individually; this list does not authorize starting the next feature automatically.
- Category maintenance CRUD with optional one-level subcategories, complete product maintenance CRUD with a price and optional image plus paginated administration listing, administrator provisioning/maintenance and login/session protection, named test fixtures, and the PR workflow file are implemented. PR merge enforcement still requires owner configuration. Variants and public catalogue browsing remain separate work.

## Later phases

### Phase 2: catalogue operations and discoverability

Possible features include stronger filtering and PostgreSQL-backed search, improved image management, SEO improvements, analytics, and richer customer enquiry features. Each should be prioritized using feedback from the business and its customers.

### Phase 3: commerce discovery

Before building commerce, validate business processes such as pricing, stock accuracy, delivery or collection, returns, taxes, payment providers, and who will operate the system. Only then define carts, accounts, orders, payments, notifications, and inventory behavior.

### Phase 4: e-commerce

If validation supports it, evolve the catalogue into transactional e-commerce. At that point the project may require customer identity, payment integration, transactional email, stronger auditing, operational monitoring, and more rigorous security and data-protection controls.

Dedicated search, caching, queues, or additional services should still be introduced only in response to measured scale or concrete requirements.

## Initial success criteria

The first release is successful when:

- The family business has a public, shareable catalogue.
- Customers can browse accurate products comfortably on a phone.
- Product pages communicate enough information for customers to contact or otherwise engage with the business.
- The project owner can keep catalogue data current through the private admin area.
- The application can be deployed and maintained within the owner's available time and budget.
- Basic failures can be detected and diagnosed without exposing sensitive information.

## Open product questions

Resolve these with the family business before locking down the domain model or endpoint contract:

1. What category list best reflects how customers look for this wide range of products?
2. Besides name, description, images, variants, and price, which details must be supported initially?
3. How should flexible details such as ingredients, dimensions, materials, scent, or usage instructions be represented?
4. Should a product card show the lowest variant price as `From R...`, or should one variant be selected as its advertised default?
5. What exact WhatsApp number and pre-filled message format should the catalogue use?
6. Should inactive variants be hidden individually while the rest of a product remains visible?
7. Which location, delivery area, contact details, and business hours should be public?
8. Administrator provisioning and initial recovery are implemented through owner-only terminal commands: creation, reset, disable/reactivate, and paginated user listing. No email or invitation workflow is needed initially.
9. What privacy, accessibility, language, or local regulatory requirements apply?

## Decision record

Record meaningful product and architecture decisions here until a dedicated decision-record format is needed.

| Date | Decision | Status | Reason |
| --- | --- | --- | --- |
| 2026-08-09 | Launch as a public read-only catalogue before attempting e-commerce. | Accepted | Delivers value to the family business sooner and keeps the part-time project achievable. |
| 2026-08-09 | Use Nuxt for the frontend and Spring Boot for the API. | Partially superseded 2026-10-24 | Spring Boot remains selected; the frontend framework decision has been reopened. |
| 2026-08-09 | Use PostgreSQL for structured catalogue data. | Accepted | Fits the current Spring Data JPA backend and leaves room for later transactional features. |
| 2026-08-09 | Store images outside PostgreSQL and persist their URLs or metadata. | Accepted | Keeps relational storage focused on structured data and allows efficient media delivery. |
| 2026-08-09 | Choose cloud providers closer to deployment. | Open | Free tiers and operational constraints are time-sensitive and require fresh comparison. |
| 2026-08-09 | Model products with separately priced variants. | Accepted | The business sells products in different weights, sizes, and pack quantities. |
| 2026-08-09 | Hide inactive products from public catalogue operations. | Accepted | Customers should only see products currently offered by the business. |
| 2026-08-09 | Use WhatsApp enquiries as the initial ordering action. | Accepted | It supports real customer orders without prematurely building carts, checkout, or payments. |
| 2026-08-09 | Use one frontend application with public and protected admin areas. | Accepted; framework reopened 2026-10-24 | It minimizes deployment and maintenance overhead while allowing the owner to manage catalogue data. |
| 2026-08-09 | Do not offer public customer accounts in the catalogue phase. | Accepted | Anonymous visitors only need to browse and contact the business; accounts would not yet provide value. |
| 2026-10-24 | Defer frontend framework selection; consider React or Next.js alongside Nuxt. | Accepted | API development can proceed independently of the frontend framework. |
| 2026-10-24 | Prioritize small, tested API features before frontend work; defer hosting research. | Accepted | About two hours on weekends requires focused delivery and minimal context switching. |
| 2026-10-24 | Create categories with numeric IDs, trimmed case-insensitive unique names, optional descriptions, and active status; defer category URL names. | Accepted | Keeps the first operation small and prevents duplicate catalogue categories. |
| 2026-10-24 | Hide inactive categories and their products from public browsing. | Accepted; implementation deferred to browsing | Public customers should see only categories and products currently offered. |
| 2026-10-24 | Use Flyway SQL migrations and PostgreSQL Testcontainers integration tests. | Accepted | Makes schema changes explicit and tests the full operation against the actual database engine. |
| 2026-10-24 | Keep HTTP DTO mapping in controllers and translate recognized persistence failures into business exceptions in services. | Accepted | Each layer handles its own concerns without blanket exception catching or database-specific HTTP handlers. |
| 2026-10-24 | Favor practical code reuse and fewer purposeful types; avoid speculative abstractions. | Accepted | Keeps the Java codebase understandable and manageable within the owner's weekend time budget. |
| 2026-10-24 | Prefer one shared business exception, explicit error codes, and a central HTTP handler over per-error exception classes. | Accepted; implemented | Reduces file proliferation while keeping business failures distinct from HTTP responses and message text. |
| 2026-10-24 | Agree data fields and operation behaviour first, then build one operation through the full stack. | Accepted | Keeps iterations small while allowing related records to participate in one transaction. |
| 2026-10-24 | Prefer backward-compatible API and schema changes while retaining explicit migrations. | Accepted | Existing callers and stored data should continue to work as the catalogue evolves. |
| 2026-10-24 | Add PR test automation and require passing tests before merging. | Workflow prepared; hosted run and owner-managed merge enforcement pending | The workflow runs Maven verification; GitHub requires a separate required-check rule to block merging on failure. |
| 2026-10-24 | Update categories using PUT with required name and active status, optional description, and no upsert. | Accepted; implemented | Provides one complete maintenance operation while reusing the existing category stack and preserving stored data on failures. |
| 2026-10-24 | Allow authenticated category reads by numeric ID, including inactive categories. | Accepted; implemented | Supports catalogue maintenance while public browsing and dedicated admin security remain separate features. |
| 2026-10-24 | List all categories for administration in pages sorted by ID, defaulting to 20 items and capped at 100 per page. | Accepted; implemented | Supports an admin list without unbounded responses or exposing persistence-specific page formats. |
| 2026-10-24 | Permanently delete categories through authenticated, CSRF-protected DELETE; return 204 on success and 404 if missing. | Accepted; implemented | Completes category maintenance CRUD; active status remains the alternative for hiding categories without removing them. |
| 2026-10-24 | Reject category pagination offsets above JPA's integer limit with 400. | Accepted; implemented | Prevents otherwise valid page/size values from reaching an unhandled persistence error; values at the supported boundary remain allowed. |
| 2026-10-31 | Give simple products their own price and make variants optional. | Accepted; simple-product creation implemented, variants deferred | Products without size or other options should not require a synthetic variant. |
| 2026-10-31 | Support optional one-level subcategories through parentId; block deleting parents with children. | Accepted; implemented | Allows the broad catalogue to be grouped as real products are added while preserving existing categories and preventing deeper nesting. |
| 2026-10-31 | Create products with a positive rand price, an existing category, and one optional HTTPS image URL and alternative text; block deleting categories with products. | Accepted; implemented | Makes a simple catalogue product useful immediately without requiring variants, uploads, or a media-provider dependency. |
| 2026-10-31 | Allow authenticated product reads by numeric ID, including inactive products and nullable image metadata. | Accepted; implemented | Supports private catalogue maintenance while reusing the product response and keeping public browsing separate. |
| 2026-10-31 | List all products for administration in pages sorted by ID, defaulting to 20 items, capped at 100, and limited to JPA's supported offset. | Accepted; implemented | Gives the admin catalogue a bounded list using the same pagination contract as categories, with public browsing kept separate. |
| 2026-10-31 | Replace product fields through authenticated, CSRF-protected PUT, requiring active, clearing omitted optional fields, and allowing category changes. | Accepted; implemented | Completes product editing while preserving IDs, validation rules, transactional rollback, and category deletion protection. |
| 2026-10-31 | Permanently delete products through authenticated, CSRF-protected DELETE, returning 204 on success and 404 when missing. | Accepted; implemented | Completes product maintenance CRUD while retaining category records and external image files; active status remains the alternative for hiding products. |
| 2026-10-31 | Reject incorrect JSON types for numeric IDs and active flags, and map product image errors to the actual request fields. | Accepted; implemented | Prevents silent truncation or conversion of invalid values and gives frontend forms consistent field-error keys. |
| 2026-11-08 | Create admin accounts through an owner-only interactive terminal command; store Argon2id hashes and normalized unique emails, with a visible local password blocklist. | Accepted; implemented | Provides controlled account provisioning without public signup, email delivery, or manually prepared password hashes. |
| 2026-11-08 | Use server-side sessions with a 30-minute inactivity timeout, generic login failures, and a 15-minute lockout after five failures within 15 minutes; revoke sessions on password reset or disabling. | Accepted; implemented | Fits the browser-based admin area while keeping customer browsing anonymous and making account access revocable. |
| 2026-11-08 | Share one `users` table with a required role instead of keeping an admin-specific account table. | Accepted; implemented through V5 | Preserves existing admin accounts while allowing future customer accounts to use the same table; the current setup command explicitly creates admins and no customer signup is introduced. |
| 2026-11-08 | Provide JSON admin login/logout, CSRF and current-user endpoints; enforce admin sessions on maintenance and persist rolling lockout state in PostgreSQL. | Accepted; implemented | Replaces default authentication with real user accounts while keeping browser sessions, CSRF, safe errors, and concurrent failure counting. |
| 2026-11-08 | Provide operator-only password reset, disable/reactivate, and paginated user listing; use a persisted session version checked on every authenticated request. | Accepted; implemented | Revokes sessions across the separate command/API processes without adding infrastructure; recovery retains account identity and has no public endpoint. |
| 2026-11-08 | Add one-hour absolute login expiry, bounded login/session admission, and secure cookies in the `prod` profile. | Accepted; implemented | Limits use of stolen sessions and bounds CPU, database, and session-memory work without new infrastructure; retains local HTTP and account lockout. |
