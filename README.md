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

Product tests use the same real HTTP/PostgreSQL approach. Both suites share `ApiTestClient` for requests, real login, and CSRF handling. Immutable product starting data lives in `src/test/java/com/pickaspice/api/product/ProductFixtures.java`; category fixtures are reused for product setup. Tests clear products before categories both before and after each test to respect foreign keys.

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

All category endpoints require authentication. Write operations also require CSRF protection; GET does not require a CSRF token. Dedicated admin access rules and public browsing are not implemented yet; they remain separate features.

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
