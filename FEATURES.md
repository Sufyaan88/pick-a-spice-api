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

- `Product`: stable identifier, name, slug, description, category, active status, and timestamps
- `ProductVariant`: a customer-facing label such as `100 g`, `500 ml`, `Pack of 100`, or `Large`, plus price, active status, and an optional SKU
- `ProductImage`: external URL, alternative text, display order, and an indication of the primary image
- `Category`: generated numeric ID, name, active status, and optional description; no URL name in the initial implementation
- Product specifications: optional structured details for information that varies by product type

A **slug** is a short, URL-friendly identifier derived from a readable name. For example, a product named `Black Seed Oil 100ml` could use the slug `black-seed-oil-100ml`, producing a page URL such as `/products/black-seed-oil-100ml`. Slugs avoid exposing database identifiers in customer-facing URLs and make links easier to read and share. They must be unique and should remain stable when possible.

For catalogue cards, a product with one active variant can show that variant's price. A product with several differently priced active variants can display the lowest price as `From R...`. The detail page presents the available variants and their exact prices.

The exact representation of flexible specifications still needs design. Avoid adding spice-specific columns that do not make sense for bags, books, fragrances, knives, or other categories.

### Agreed category creation contract

- `POST /api/admin/categories` creates one category and returns `201` with its generated `id`, `name`, `description`, and `active` status.
- `name` is required, trimmed before validation and storage, and limited to 100 characters after trimming. Names are unique regardless of capitalization; surrounding whitespace does not distinguish names.
- `description` is optional, nullable, and limited to 1,000 characters.
- `active` defaults to `true` when omitted or null; explicit `false` creates an inactive category.
- Blank names and excessive lengths return `400` with field errors. Malformed JSON returns `400`. Duplicate names return `409`. Application errors use problem-detail JSON.
- PostgreSQL enforces name uniqueness, including concurrent requests. Flyway manages the schema; Hibernate validates it rather than creating or updating it.
- The controller maps HTTP request and response DTOs. The service translates duplicate-name persistence failures into a shared `BusinessException` with `ErrorCode.CATEGORY_NAME_EXISTS`, preserving the cause. The central `ApiExceptionHandler` maps that code to `409`; `ErrorCode` holds the safe business-error message without HTTP dependencies. Validation and malformed-JSON handling also use the central handler.
- Creation currently retains Spring Security's default authentication and CSRF protections. Dedicated administrator authentication and authorization remain a separate feature; the `/admin` path alone does not enforce an administrator role.
- Tests exercise a real HTTP server, real authentication, real application components, and disposable PostgreSQL through Testcontainers. No collaborators are mocked.

### Agreed category update contract

- `PUT /api/admin/categories/{id}` replaces the editable fields of an existing category and returns `200` with the saved category. It does not create missing categories or change the numeric ID.
- `name` uses the same trimming, length, and case-insensitive uniqueness rules as creation. Keeping the category's own name is allowed.
- `description` is optional, limited to 1,000 characters, and cleared when omitted or null.
- `active` must be supplied as a non-null boolean. Both activation and deactivation are supported.
- Invalid input returns `400`, a missing category returns `404`, and another category's name returns `409`. Rejected updates leave stored records unchanged; the update runs in one transaction.
- Create and update reuse `CategoryRequest`, `CategoryResponse`, and the shared persistence/error handling. A validation group requires `active` on updates while preserving the create default.
- Default authentication and CSRF protection remain in place. Public visibility of inactive categories will be implemented with browsing.

### Agreed category read-by-ID contract

- `GET /api/admin/categories/{id}` returns `200` with the saved category's `id`, `name`, `description`, and `active` status. Both active and inactive categories can be read through this authenticated maintenance endpoint.
- Missing categories return `404` with the shared category-not-found error. Non-numeric IDs return `400`. Anonymous JSON requests return `401` without category data.
- Reads leave stored data unchanged and do not require a CSRF token. Existing default authentication remains in place; dedicated administrator authorization is still a separate feature.
- The operation reuses `CategoryResponse`, `CategoryRepository`, and shared error handling. Update and read share the same category lookup; no new production types or migrations are needed.

### Agreed category administration-list contract

- `GET /api/admin/categories?page=0&size=20` returns `200` with `items`, `page`, `size`, and `totalCount`. Items use the existing category response fields; totalCount counts all categories, not just the current page.
- Includes active and inactive categories, ordered by numeric ID ascending. Pagination is performed in PostgreSQL rather than loading the entire catalogue into memory.
- Pages start at zero and default to zero. Size defaults to 20 and must be between 1 and 100. Negative pages, invalid sizes, non-integer values, and values outside the integer range return `400` problem-detail JSON.
- The requested offset (`page * size`) must not exceed 2,147,483,647, JPA's maximum supported offset. Larger offsets return `400` problem-detail JSON using the shared `PAGE_OFFSET_TOO_LARGE` code, before querying the repository. Calculations use a long value so multiplying valid integer parameters cannot overflow silently.
- Within the supported offset range, an empty catalogue or a page beyond the available results returns an empty items array with the requested page information and the total category count.
- Authentication is required; anonymous JSON requests return `401`. GET requires no CSRF token and leaves data unchanged. Dedicated admin authorization remains separate.
- Reuses the category repository, service, and response mapping, plus `common.PageResponse<T>` for a stable reusable HTTP envelope. No schema migration is needed.

### Agreed category deletion contract

- `DELETE /api/admin/categories/{id}` permanently removes an existing category and returns `204` with an empty response body. Both active and inactive categories can be deleted.
- Missing categories return `404` using the existing category-not-found error. Non-numeric IDs and IDs outside the numeric range return `400`.
- Authentication and CSRF protection remain required. Anonymous requests with a valid CSRF token return `401`; signed-in requests without a CSRF token return `403`.
- Successful deletion removes the database row and leaves other categories unchanged. Rejected requests leave existing data unchanged. Use `active: false` to hide a category without permanently removing it.
- Reuses the category lookup, repository, shared error handling, and named test fixtures. No new production classes or schema migration are needed. Product relationships do not exist yet; their deletion constraints must be agreed when that feature is designed.

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

### Not yet selected

- Frontend framework: Nuxt, React, or Next.js; decide when frontend work begins
- Production hosts for the frontend and API
- Managed PostgreSQL provider
- Image CDN or object-storage provider
- Authentication and session approach for the private admin area
- Deployment automation and topology; a GitHub Actions PR test workflow is prepared separately

Hosting and deployment-provider research is outside the current API-development focus. Provider pricing, free tiers, inactivity policies, regional availability, and card requirements change over time. Research them at the time of selection; the earlier discussion of services such as Neon, Supabase, Cloudinary, Cloudflare R2, Render, or Vercel is background, not a binding decision.

### Development status and pending work

- Automated test setup uses named immutable category fixtures and real HTTP setup helpers. Each test creates fresh records as needed and cleans database mutations before and after execution; boundary and invalid-input scenarios remain explicit. These fixtures are test-only, not development seed data or customer accounts.
- Implemented: create category, update category, authenticated read by ID, paginated administration list, permanent deletion, field validation, PostgreSQL uniqueness, Flyway migrations, and real HTTP/PostgreSQL tests with explicit Arrange/Act/Assert sections. Request/response mapping is at the controller boundary; persistence failures are translated in the service.
- Last observed verification on 2026-10-24: all 76 tests passed (75 category integration tests plus the startup test) and Maven `verify` succeeded. Pagination-offset regression tests first reproduced `500` responses, then confirmed deliberate `400` responses above JPA's limit and successful reads at the supported boundary. This records the completed run, not a guarantee about later changes.
- Implemented: shared `BusinessException`, `ErrorCode`, and central `ApiExceptionHandler` replace category-specific exception and handler classes. Existing response formats are preserved; codes are internal and are not added to response bodies.
- Dedicated admin security remains its own feature. Public browsing and inactive-category visibility are not implemented yet.
- Prepared: `.github/workflows/pr-tests.yml` runs Java 21, Docker-backed PostgreSQL tests, and Maven verification on PR creation and updates, using the stable check name `Maven verification`. Workflow syntax was checked locally with actionlint. The first GitHub-hosted run is not yet observed. The owner must push/open the PR and configure the check as required in branch protection or an active ruleset to block merging on failure; repository settings have not been changed.
- Future catalogue operations will be agreed and built individually; this list does not authorize starting the next feature automatically.
- Category maintenance CRUD, named test fixtures, and the PR workflow file are implemented. PR merge enforcement still requires owner configuration. Public catalogue browsing, dedicated admin security, and products remain separate work.

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
8. How should the first administrator account be provisioned and recovered safely?
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
