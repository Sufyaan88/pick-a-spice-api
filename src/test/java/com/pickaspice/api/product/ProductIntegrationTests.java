package com.pickaspice.api.product;

import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.pickaspice.api.ApiTestClient;
import com.pickaspice.api.TestDatabaseConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.pickaspice.api.category.CategoryFixtures.HERBAL_OILS;
import static com.pickaspice.api.category.CategoryFixtures.HERBAL_PRODUCTS;
import static com.pickaspice.api.category.CategoryFixtures.INACTIVE_INCENSE;
import static com.pickaspice.api.product.ProductFixtures.BLACK_SEED_OIL;
import static com.pickaspice.api.product.ProductFixtures.PAGINATION_PRODUCTS;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.security.user.name=category-test-user",
		"spring.security.user.password=category-test-password"
})
@Import(TestDatabaseConfiguration.class)
class ProductIntegrationTests {

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper json;

	private ApiTestClient client;

	@BeforeEach
	void prepareRequestClientAndDatabase() {
		client = new ApiTestClient(port);
		jdbc.update("delete from products");
		jdbc.update("delete from categories");
	}

	@AfterEach
	void cleanDatabaseAndCloseRequestClient() {
		try {
			jdbc.update("delete from products");
			jdbc.update("delete from categories");
		}
		finally {
			client.close();
		}
	}

	@Test
	void createsProductWithImageAndCommitsItToPostgres() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = productRequest(categoryId);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("id").asLong()).isPositive();
		assertThat(body.path("name").asText()).isEqualTo("Black Seed Oil");
		assertThat(body.path("description").asText()).isEqualTo("100 ml bottle");
		assertThat(new BigDecimal(body.path("price").asText())).isEqualByComparingTo("45.00");
		assertThat(body.path("categoryId").asLong()).isEqualTo(categoryId);
		assertThat(body.path("active").asBoolean()).isTrue();
		assertThat(body.path("imageUrl").asText()).isEqualTo(request.get("imageUrl"));
		assertThat(body.path("imageAltText").asText()).isEqualTo(request.get("imageAltText"));
		Map<String, Object> saved = jdbc.queryForMap("select * from products where id = ?", body.path("id").asLong());
		assertThat(saved).containsEntry("name", "Black Seed Oil").containsEntry("description", "100 ml bottle")
				.containsEntry("category_id", categoryId).containsEntry("active", true)
				.containsEntry("image_url", request.get("imageUrl")).containsEntry("image_alt_text", request.get("imageAltText"));
		assertThat((BigDecimal) saved.get("price")).isEqualByComparingTo("45.00");
		assertThat(productCount()).isEqualTo(1);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void defaultsOmittedOrNullOptionalFields(boolean explicitNull) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		Map<String, Object> request = new LinkedHashMap<>(Map.of("name", "Black Seed Oil", "price", 45, "categoryId", categoryId));
		if (explicitNull) {
			for (String field : List.of("description", "active", "imageUrl", "imageAltText")) {
				request.put(field, null);
			}
		}

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("active").asBoolean()).isTrue();
		for (String field : List.of("description", "imageUrl", "imageAltText")) {
			assertThat(body.path(field).isNull()).as(field).isTrue();
		}
		assertThat(jdbc.queryForMap("select * from products")).containsEntry("active", true)
				.containsEntry("description", null).containsEntry("image_url", null).containsEntry("image_alt_text", null);
	}

	@Test
	void createsInactiveProductInInactiveCategory() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(INACTIVE_INCENSE);
		Map<String, Object> request = productRequest(categoryId);
		request.put("active", false);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json.readTree(response.body()).path("active").asBoolean()).isFalse();
		assertThat(jdbc.queryForMap("select * from products")).containsEntry("active", false)
				.containsEntry("category_id", categoryId);
	}

	@Test
	void assignsProductToSubcategoryBeneathInactiveParent() throws Exception {
		// Arrange
		long parentId = givenCategoryId(INACTIVE_INCENSE);
		Map<String, Object> child = new LinkedHashMap<>(HERBAL_OILS);
		child.put("parentId", parentId);
		long categoryId = givenCategoryId(child);
		Map<String, Object> request = productRequest(categoryId);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json.readTree(response.body()).path("categoryId").asLong()).isEqualTo(categoryId);
		assertThat(jdbc.queryForObject("select category_id from products", Long.class)).isEqualTo(categoryId);
	}

	@Test
	void trimsProductNameBeforeValidatingMaximumLength() throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put("name", "  " + "a".repeat(200) + "  ");

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json.readTree(response.body()).path("name").asText()).isEqualTo("a".repeat(200));
		assertThat(jdbc.queryForObject("select name from products", String.class)).isEqualTo("a".repeat(200));
	}

	@Test
	void acceptsMaximumLengthsAndPrice() throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		String imagePrefix = "https://images.example.test/";
		request.put("description", "d".repeat(5000));
		request.put("imageUrl", imagePrefix + "a".repeat(2048 - imagePrefix.length()));
		request.put("imageAltText", "a".repeat(200));
		request.put("price", new BigDecimal("9999999999.99"));

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		Map<String, Object> saved = jdbc.queryForMap("select * from products");
		assertThat(saved).containsEntry("description", request.get("description"))
				.containsEntry("image_url", request.get("imageUrl")).containsEntry("image_alt_text", request.get("imageAltText"));
		assertThat((BigDecimal) saved.get("price")).isEqualByComparingTo("9999999999.99");
	}

	@Test
	void acceptsOneCentAndImageWithoutAlternativeText() throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put("price", new BigDecimal("0.01"));
		request.remove("imageAltText");

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat((BigDecimal) jdbc.queryForMap("select * from products").get("price")).isEqualByComparingTo("0.01");
		assertThat(json.readTree(response.body()).path("imageAltText").isNull()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "price", "categoryId"})
	void rejectsOmittedRequiredFields(String field) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.remove(field);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, field);
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "price", "categoryId"})
	void rejectsNullRequiredFields(String field) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put(field, null);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, field);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t\n"})
	void rejectsBlankProductNames(String name) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put("name", name);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "name");
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-0.01", "1.001", "10000000000.00"})
	void rejectsInvalidPricesWithoutRoundingOrSaving(String price) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put("price", new BigDecimal(price));

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "price");
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "description", "imageUrl", "imageAltText"})
	void rejectsExcessiveFieldLengths(String field) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		int maximum = switch (field) {
			case "description" -> 5000;
			case "imageUrl" -> 2048;
			default -> 200;
		};
		String prefix = field.equals("imageUrl") ? "https://images.example.test/" : "";
		request.put(field, prefix + "a".repeat(maximum + 1 - prefix.length()));

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, field);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "http://images.example.test/a.jpg", "ftp://images.example.test/a.jpg",
			"/a.jpg", "https://", "https:///a.jpg", "https://images.example.test/a b.jpg", "not a URL"})
	void rejectsInvalidImageUrls(String imageUrl) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put("imageUrl", imageUrl);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "imageUrl");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void rejectsAlternativeTextWithoutAnImage(boolean explicitNull) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		if (explicitNull) {
			request.put("imageUrl", null);
		}
		else {
			request.remove("imageUrl");
		}

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "imageAltText");
	}

	@Test
	void rejectsMissingCategoryWithoutSaving() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		Map<String, Object> request = productRequest(categoryId + 1);
		var before = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Category not found.");
		assertThat(productCount()).isZero();
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Black Seed Oil", "black seed oil", " BLACK SEED OIL "})
	void rejectsDuplicateProductNamesAcrossCategories(String name) throws Exception {
		// Arrange
		long firstCategory = givenCategoryId(HERBAL_OILS);
		givenProduct(productRequest(firstCategory));
		long otherCategory = givenCategoryId(HERBAL_PRODUCTS);
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = productRequest(otherCategory);
		request.put("name", name);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertProblem(response, 409);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("A product with this name already exists.");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "{\"categoryId\":\"invalid\"}", "{\"categoryId\":9223372036854775808}",
			"{\"price\":\"invalid\"}"})
	void rejectsMalformedProductRequests(String body) throws Exception {
		// Arrange
		givenCategoryId(HERBAL_OILS);

		// Act
		HttpResponse<String> response = send("POST", "/api/admin/products", body, true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(productCount()).isZero();
	}

	@Test
	void rejectsAnonymousProductCreation() throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));

		// Act
		HttpResponse<String> response = send("POST", "/api/admin/products", json.writeValueAsString(request), false, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(productCount()).isZero();
	}

	@Test
	void rejectsProductCreationWithoutCsrfToken() throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		signIn();

		// Act
		HttpResponse<String> response = send("POST", "/api/admin/products", json.writeValueAsString(request), true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(productCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void rejectsDeletingCategoryContainingActiveOrInactiveProduct(boolean active) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		Map<String, Object> request = productRequest(categoryId);
		request.put("active", active);
		givenProduct(request);
		var productsBefore = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + categoryId, "", true, true);

		// Assert
		assertProblem(response, 409);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("A category with products cannot be deleted.");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(productsBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@Test
	void concurrentCreationAndCategoryDeletionDoNotLeaveOrphans() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		Map<String, Object> request = productRequest(categoryId);
		csrfToken();

		// Act
		List<HttpResponse<String>> responses;
		try (var requests = Executors.newFixedThreadPool(2)) {
			var results = requests.<HttpResponse<String>>invokeAll(List.of(() -> create(request),
					() -> send("DELETE", "/api/admin/categories/" + categoryId, "", true, true)));
			responses = List.of(results.get(0).get(), results.get(1).get());
		}

		// Assert
		assertThat(responses.stream().map(HttpResponse::statusCode).toList())
				.isIn(List.of(201, 409), List.of(404, 204));
		assertThat(jdbc.queryForObject("""
				select count(*) from products p left join categories c on p.category_id = c.id where c.id is null
				""", Long.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void readsProductByIdIncludingInactiveProducts(boolean active) throws Exception {
		// Arrange
		Map<String, Object> request = productRequest(givenCategoryId(HERBAL_OILS));
		request.put("active", active);
		JsonNode expected = givenProduct(request);
		var productsBefore = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products/" + expected.path("id").asLong(), "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		assertProductResponse(json.readTree(response.body()), expected);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(productsBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@Test
	void readsProductWithNullOptionalFieldsInInactiveCategory() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(INACTIVE_INCENSE);
		JsonNode expected = givenProduct(Map.of("name", "Incense Sticks", "price", 25, "categoryId", categoryId));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products/" + expected.path("id").asLong(), "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertProductResponse(body, expected);
		assertThat(body.path("description").isNull()).isTrue();
		assertThat(body.path("imageUrl").isNull()).isTrue();
		assertThat(body.path("imageAltText").isNull()).isTrue();
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void returnsNotFoundForMissingProductWithoutChangingData() throws Exception {
		// Arrange
		JsonNode product = givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		long missingId = product.path("id").asLong() + 1;
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products/" + missingId, "", true, false);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Product not found.");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-number", "9223372036854775808"})
	void rejectsReadingWithInvalidProductId(String invalidId) throws Exception {
		// Arrange
		givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products/" + invalidId, "", true, false);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void rejectsAnonymousProductReadWithoutExposingData() throws Exception {
		// Arrange
		JsonNode product = givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products/" + product.path("id").asLong(), "", false, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).doesNotContain("Black Seed Oil", "100 ml bottle", "images.example.test");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void listsEmptyProductPageWithDefaults() throws Exception {
		// Arrange
		String path = "/api/admin/products";

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("items").isArray()).isTrue();
		assertThat(body.path("items").size()).isZero();
		assertThat(body.path("page").asInt()).isZero();
		assertThat(body.path("size").asInt()).isEqualTo(20);
		assertThat(body.path("totalCount").asLong()).isZero();
		assertThat(productCount()).isZero();
	}

	@Test
	void limitsDefaultProductPageTo20Items() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		List<JsonNode> expected = givenProducts(ProductFixtures.numberedProducts(21), categoryId);
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products", "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("items").size()).isEqualTo(20);
		for (int index = 0; index < 20; index++) {
			assertProductResponse(body.path("items").get(index), expected.get(index));
		}
		assertThat(body.path("page").asInt()).isZero();
		assertThat(body.path("size").asInt()).isEqualTo(20);
		assertThat(body.path("totalCount").asLong()).isEqualTo(21);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void listsProductsByIdAcrossPagesIncludingInactiveProductsAndCategories(int page) throws Exception {
		// Arrange
		long inactiveCategoryId = givenCategoryId(INACTIVE_INCENSE);
		Map<String, Object> child = new LinkedHashMap<>(HERBAL_OILS);
		child.put("parentId", inactiveCategoryId);
		long childId = givenCategoryId(child);
		long activeCategoryId = givenCategoryId(HERBAL_PRODUCTS);
		List<Long> categories = List.of(inactiveCategoryId, childId, activeCategoryId);
		List<JsonNode> expected = new ArrayList<>();
		for (int index = 0; index < PAGINATION_PRODUCTS.size(); index++) {
			Map<String, Object> request = new LinkedHashMap<>(PAGINATION_PRODUCTS.get(index));
			request.put("categoryId", categories.get(index));
			expected.add(givenProduct(request));
		}
		var productsBefore = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products?page=" + page + "&size=2", "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		int start = Math.min(page * 2, expected.size());
		int end = Math.min(start + 2, expected.size());
		assertThat(body.path("items").isArray()).isTrue();
		assertThat(body.path("items").size()).isEqualTo(end - start);
		for (int index = start; index < end; index++) {
			assertProductResponse(body.path("items").get(index - start), expected.get(index));
		}
		assertThat(body.path("page").asInt()).isEqualTo(page);
		assertThat(body.path("size").asInt()).isEqualTo(2);
		assertThat(body.path("totalCount").asLong()).isEqualTo(3);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(productsBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 100})
	void acceptsProductPageSizeBoundaries(int size) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		List<JsonNode> expected = givenProducts(PAGINATION_PRODUCTS, categoryId);

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products?size=" + size, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("page").asInt()).isZero();
		assertThat(body.path("size").asInt()).isEqualTo(size);
		assertThat(body.path("totalCount").asLong()).isEqualTo(3);
		assertThat(body.path("items").size()).isEqualTo(Math.min(size, expected.size()));
		for (int index = 0; index < Math.min(size, expected.size()); index++) {
			assertProductResponse(body.path("items").get(index), expected.get(index));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc",
			"page=1.5", "size=1.5", "page=2147483648", "size=2147483648"})
	void rejectsInvalidProductPaginationWithoutChangingData(String query) throws Exception {
		// Arrange
		givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products?" + query, "", true, false);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=2147483647&size=100", "page=2147483647&size=2",
			"page=2147483647", "page=1073741824&size=2"})
	void rejectsProductPageOffsetsBeyondJpaLimit(String query) throws Exception {
		// Arrange
		givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products?" + query, "", true, false);

		// Assert
		assertProblem(response, 400);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Requested page offset is too large.");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=2147483647&size=1", "page=1073741823&size=2"})
	void acceptsProductPageOffsetsWithinJpaLimit(String query) throws Exception {
		// Arrange
		givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products?" + query, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("items").isArray()).isTrue();
		assertThat(body.path("items").size()).isZero();
		assertThat(body.path("page").asInt()).isEqualTo(Integer.parseInt(query.split("[=&]")[1]));
		assertThat(body.path("size").asInt()).isEqualTo(Integer.parseInt(query.split("[=&]")[3]));
		assertThat(body.path("totalCount").asLong()).isEqualTo(1);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void rejectsAnonymousProductListWithoutExposingData() throws Exception {
		// Arrange
		givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/products", "", false, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).doesNotContain("Black Seed Oil", "100 ml bottle", "images.example.test");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void updatesProductFieldsAndCategoryWithoutChangingOtherRecords(boolean subcategory) throws Exception {
		// Arrange
		long originalCategoryId = givenCategoryId(HERBAL_OILS);
		List<JsonNode> products = givenProducts(PAGINATION_PRODUCTS, originalCategoryId);
		long id = products.getFirst().path("id").asLong();
		long destinationId = givenCategoryId(INACTIVE_INCENSE);
		if (subcategory) {
			Map<String, Object> child = new LinkedHashMap<>(HERBAL_PRODUCTS);
			child.put("parentId", destinationId);
			destinationId = givenCategoryId(child);
		}
		var othersBefore = jdbc.queryForList("select * from products where id <> ? order by id", id);
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = updateRequest(destinationId);
		request.put("name", "  Updated Black Seed Oil  ");
		request.put("description", "200 ml bottle");
		request.put("price", new BigDecimal("80.50"));
		request.put("active", false);
		request.put("imageUrl", "https://images.example.test/updated-oil.jpg");
		request.put("imageAltText", "Updated 200 ml bottle");

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		Map<String, Object> expected = new LinkedHashMap<>(request);
		expected.put("name", "Updated Black Seed Oil");
		expected.put("id", id);
		assertProductResponse(json.readTree(response.body()), json.valueToTree(expected));
		assertThat(savedProduct(id)).containsEntry("name", "Updated Black Seed Oil")
				.containsEntry("description", "200 ml bottle").containsEntry("category_id", destinationId)
				.containsEntry("active", false).containsEntry("image_url", request.get("imageUrl"))
				.containsEntry("image_alt_text", request.get("imageAltText"));
		assertThat((BigDecimal) savedProduct(id).get("price")).isEqualByComparingTo("80.50");
		assertThat(jdbc.queryForList("select * from products where id <> ? order by id", id)).isEqualTo(othersBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
		assertThat(productCount()).isEqualTo(3);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Black Seed Oil", "black seed oil", " BLACK SEED OIL "})
	void allowsKeepingOwnProductNameOnUpdate(String name) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		Map<String, Object> request = updateRequest(categoryId);
		request.put("name", name);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(savedProduct(id)).containsEntry("name", name.strip());
		assertThat(productCount()).isEqualTo(1);
	}

	@Test
	void reactivatesProductOnUpdate() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		Map<String, Object> original = productRequest(categoryId);
		original.put("active", false);
		long id = givenProduct(original).path("id").asLong();
		Map<String, Object> request = updateRequest(categoryId);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("active").asBoolean()).isTrue();
		assertThat(savedProduct(id)).containsEntry("active", true);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void clearsOmittedOrNullOptionalFieldsOnUpdate(boolean explicitNull) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		Map<String, Object> request = new LinkedHashMap<>(Map.of(
				"name", "Black Seed Oil", "price", 45, "categoryId", categoryId, "active", true));
		if (explicitNull) {
			for (String field : List.of("description", "imageUrl", "imageAltText")) {
				request.put(field, null);
			}
		}

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		for (String field : List.of("description", "imageUrl", "imageAltText")) {
			assertThat(body.path(field).isNull()).as(field).isTrue();
		}
		assertThat(savedProduct(id)).containsEntry("description", null)
				.containsEntry("image_url", null).containsEntry("image_alt_text", null);
	}

	@Test
	void updatesImageWhileClearingOmittedAlternativeText() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		Map<String, Object> request = updateRequest(categoryId);
		request.put("imageUrl", "https://images.example.test/new-image.jpg");
		request.remove("imageAltText");

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("imageAltText").isNull()).isTrue();
		assertThat(savedProduct(id)).containsEntry("image_url", request.get("imageUrl")).containsEntry("image_alt_text", null);
	}

	@Test
	void acceptsMaximumFieldLengthsAndPriceOnUpdate() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		Map<String, Object> request = updateRequest(categoryId);
		String imagePrefix = "https://images.example.test/";
		request.put("name", "  " + "n".repeat(200) + "  ");
		request.put("description", "d".repeat(5000));
		request.put("price", new BigDecimal("9999999999.99"));
		request.put("imageUrl", imagePrefix + "i".repeat(2048 - imagePrefix.length()));
		request.put("imageAltText", "a".repeat(200));

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(savedProduct(id)).containsEntry("name", "n".repeat(200))
				.containsEntry("description", request.get("description")).containsEntry("image_url", request.get("imageUrl"))
				.containsEntry("image_alt_text", request.get("imageAltText"));
		assertThat((BigDecimal) savedProduct(id).get("price")).isEqualByComparingTo("9999999999.99");
	}

	@Test
	void acceptsOneCentPriceOnUpdate() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		Map<String, Object> request = updateRequest(categoryId);
		request.put("price", new BigDecimal("0.01"));

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat((BigDecimal) savedProduct(id).get("price")).isEqualByComparingTo("0.01");
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "price", "categoryId", "active"})
	void rejectsOmittedRequiredFieldsOnUpdate(String field) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.remove(field);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
		assertThat(json.readTree(response.body()).path("errors").path(field).asText()).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "price", "categoryId", "active"})
	void rejectsNullRequiredFieldsOnUpdate(String field) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put(field, null);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
		assertThat(json.readTree(response.body()).path("errors").path(field).asText()).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t\n"})
	void rejectsBlankNamesOnUpdate(String name) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put("name", name);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-0.01", "1.001", "10000000000.00"})
	void rejectsInvalidPricesOnUpdateWithoutRounding(String price) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put("price", new BigDecimal(price));

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
		assertThat(json.readTree(response.body()).path("errors").path("price").asText()).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "description", "imageUrl", "imageAltText"})
	void rejectsExcessiveFieldLengthsOnUpdate(String field) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		int maximum = switch (field) {
			case "description" -> 5000;
			case "imageUrl" -> 2048;
			default -> 200;
		};
		String prefix = field.equals("imageUrl") ? "https://images.example.test/" : "";
		request.put(field, prefix + "a".repeat(maximum + 1 - prefix.length()));

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
		assertThat(json.readTree(response.body()).path("errors").path(field).asText()).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://images.example.test/a.jpg", "https://"})
	void rejectsInvalidImageUrlsOnUpdate(String imageUrl) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put("imageUrl", imageUrl);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
		assertThat(json.readTree(response.body()).path("errors").path("imageUrl").asText()).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void rejectsAlternativeTextWithoutImageOnUpdate(boolean explicitNull) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		if (explicitNull) {
			request.put("imageUrl", null);
		}
		else {
			request.remove("imageUrl");
		}

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 400, before);
		assertThat(json.readTree(response.body()).path("errors").path("imageAltText").asText()).isNotBlank();
	}

	@Test
	void rejectsUpdateOfMissingProductWithoutCreatingIt() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long existingId = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);

		// Act
		HttpResponse<String> response = update(existingId + 1, request);

		// Assert
		assertRejectedUpdate(response, 404, before);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Product not found.");
	}

	@Test
	void rejectsMissingCategoryOnUpdateWithoutChangingData() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = updateRequest(categoryId + 1);
		request.put("name", "Changed");
		request.put("price", new BigDecimal("80.00"));
		request.put("active", false);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 404, before);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Category not found.");
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Packaging Bags", "packaging bags", " PACKAGING BAGS "})
	void rejectsDuplicateNamesOnUpdateAndRollsBackAllFields(String name) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		List<JsonNode> products = givenProducts(PAGINATION_PRODUCTS, categoryId);
		long id = products.getFirst().path("id").asLong();
		long otherCategoryId = givenCategoryId(INACTIVE_INCENSE);
		var before = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = updateRequest(otherCategoryId);
		request.put("name", name);
		request.put("price", new BigDecimal("80.00"));
		request.put("active", false);
		request.put("description", "Changed description");
		request.put("imageUrl", "https://images.example.test/changed.jpg");

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertRejectedUpdate(response, 409, before);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("A product with this name already exists.");
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-number", "9223372036854775808"})
	void rejectsInvalidProductIdOnUpdate(String invalidId) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		givenProduct(productRequest(categoryId));
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/products/" + invalidId, json.writeValueAsString(request), true, true);

		// Assert
		assertRejectedUpdate(response, 400, before);
	}

	@Test
	void rejectsMalformedUpdateWithoutChangingData() throws Exception {
		// Arrange
		long id = givenProduct(productRequest(givenCategoryId(HERBAL_OILS))).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/products/" + id, "{", true, true);

		// Assert
		assertRejectedUpdate(response, 400, before);
	}

	@Test
	void rejectsAnonymousProductUpdateWithoutChangingData() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put("price", new BigDecimal("80.00"));

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/products/" + id, json.writeValueAsString(request), false, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void rejectsProductUpdateWithoutCsrfToken() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		Map<String, Object> request = updateRequest(categoryId);
		signIn();

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/products/" + id, json.writeValueAsString(request), true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void concurrentProductUpdateAndDestinationCategoryDeletionDoNotLeaveOrphans() throws Exception {
		// Arrange
		long originalCategoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(originalCategoryId)).path("id").asLong();
		long destinationId = givenCategoryId(INACTIVE_INCENSE);
		Map<String, Object> request = updateRequest(destinationId);
		csrfToken();

		// Act
		List<HttpResponse<String>> responses;
		try (var requests = Executors.newFixedThreadPool(2)) {
			var results = requests.<HttpResponse<String>>invokeAll(List.of(() -> update(id, request),
					() -> send("DELETE", "/api/admin/categories/" + destinationId, "", true, true)));
			responses = List.of(results.get(0).get(), results.get(1).get());
		}

		// Assert
		assertThat(responses.stream().map(HttpResponse::statusCode).toList())
				.isIn(List.of(200, 409), List.of(404, 204));
		assertThat(savedProduct(id)).containsEntry("category_id",
				responses.getFirst().statusCode() == 200 ? destinationId : originalCategoryId);
		assertThat(jdbc.queryForObject("""
				select count(*) from products p left join categories c on p.category_id = c.id where c.id is null
				""", Long.class)).isZero();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void permanentlyDeletesProductWithoutChangingOtherRecords(boolean active) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(INACTIVE_INCENSE);
		Map<String, Object> request = productRequest(categoryId);
		request.put("active", active);
		long id = givenProduct(request).path("id").asLong();
		givenProducts(PAGINATION_PRODUCTS.subList(1, 3), categoryId);
		var othersBefore = jdbc.queryForList("select * from products where id <> ? order by id", id);
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/products/" + id, "", true, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from products where id = ?", Long.class, id)).isZero();
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(othersBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@Test
	void allowsDeletingCategoryAfterItsLastProductIsDeleted() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();

		// Act
		HttpResponse<String> productDeletion = send("DELETE", "/api/admin/products/" + id, "", true, true);
		HttpResponse<String> categoryDeletion = send("DELETE", "/api/admin/categories/" + categoryId, "", true, true);

		// Assert
		assertThat(productDeletion.statusCode()).isEqualTo(204);
		assertThat(categoryDeletion.statusCode()).isEqualTo(204);
		assertThat(productCount()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from categories", Long.class)).isZero();
	}

	@Test
	void rejectsDeletingMissingProductWithoutChangingData() throws Exception {
		// Arrange
		long id = givenProduct(productRequest(givenCategoryId(HERBAL_OILS))).path("id").asLong();
		var productsBefore = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/products/" + (id + 1), "", true, true);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).path("detail").asText()).isEqualTo("Product not found.");
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(productsBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-number", "9223372036854775808"})
	void rejectsDeletingWithInvalidProductId(String invalidId) throws Exception {
		// Arrange
		givenProduct(productRequest(givenCategoryId(HERBAL_OILS)));
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/products/" + invalidId, "", true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void rejectsAnonymousProductDeletionWithoutChangingData() throws Exception {
		// Arrange
		long id = givenProduct(productRequest(givenCategoryId(HERBAL_OILS))).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/products/" + id, "", false, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void rejectsProductDeletionWithoutCsrfToken() throws Exception {
		// Arrange
		long id = givenProduct(productRequest(givenCategoryId(HERBAL_OILS))).path("id").asLong();
		var before = jdbc.queryForList("select * from products order by id");
		signIn();

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/products/" + id, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	@Test
	void concurrentProductDeletionsReturnDeletedAndNotFound() throws Exception {
		// Arrange
		long id = givenProduct(productRequest(givenCategoryId(HERBAL_OILS))).path("id").asLong();
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		csrfToken();

		// Act
		List<HttpResponse<String>> responses;
		try (var requests = Executors.newFixedThreadPool(2)) {
			var results = requests.<HttpResponse<String>>invokeAll(List.of(
					() -> send("DELETE", "/api/admin/products/" + id, "", true, true),
					() -> send("DELETE", "/api/admin/products/" + id, "", true, true)));
			responses = List.of(results.get(0).get(), results.get(1).get());
		}

		// Assert
		assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(204, 404);
		assertThat(productCount()).isZero();
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@Test
	void concurrentProductUpdateAndDeletionDoNotRecreateProduct() throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		Map<String, Object> request = updateRequest(categoryId);
		request.put("price", new BigDecimal("80.00"));
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		csrfToken();

		// Act
		List<HttpResponse<String>> responses;
		try (var requests = Executors.newFixedThreadPool(2)) {
			var results = requests.<HttpResponse<String>>invokeAll(List.of(() -> update(id, request),
					() -> send("DELETE", "/api/admin/products/" + id, "", true, true)));
			responses = List.of(results.get(0).get(), results.get(1).get());
		}

		// Assert
		assertThat(responses.stream().map(HttpResponse::statusCode).toList()).isIn(List.of(200, 204), List.of(404, 204));
		assertThat(productCount()).isZero();
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			POST | %s.9
			PUT  | %s.9
			POST | %s.0
			PUT  | %s.0
			POST | "%s"
			PUT  | "%s"
			POST | ""
			PUT  | ""
			""")
	void rejectsNonIntegerJsonCategoryIdWithoutChangingData(String method, String idTemplate) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var productsBefore = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put("name", "Changed Product");
		request.put("categoryId", json.readTree(idTemplate.formatted(categoryId)));
		String path = "/api/admin/products" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(productsBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			POST | 1
			PUT  | 1
			POST | 0
			PUT  | 0
			POST | 1.5
			PUT  | 1.5
			POST | "true"
			PUT  | "true"
			POST | "false"
			PUT  | "false"
			POST | ""
			PUT  | ""
			""")
	void rejectsNonBooleanJsonActiveWithoutChangingData(String method, String activeJson) throws Exception {
		// Arrange
		long categoryId = givenCategoryId(HERBAL_OILS);
		long id = givenProduct(productRequest(categoryId)).path("id").asLong();
		var productsBefore = jdbc.queryForList("select * from products order by id");
		var categoriesBefore = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = updateRequest(categoryId);
		request.put("name", "Changed Product");
		request.put("active", json.readTree(activeJson));
		String path = "/api/admin/products" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(productsBefore);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(categoriesBefore);
	}

	private Map<String, Object> updateRequest(long categoryId) {
		Map<String, Object> request = productRequest(categoryId);
		request.put("active", true);
		return request;
	}

	private HttpResponse<String> update(long id, Map<String, ?> request) throws Exception {
		return send("PUT", "/api/admin/products/" + id, json.writeValueAsString(request), true, true);
	}

	private Map<String, Object> savedProduct(long id) {
		return jdbc.queryForMap("select * from products where id = ?", id);
	}

	private void assertRejectedUpdate(HttpResponse<String> response, int status, List<Map<String, Object>> before) throws Exception {
		assertProblem(response, status);
		assertThat(jdbc.queryForList("select * from products order by id")).isEqualTo(before);
	}

	private List<JsonNode> givenProducts(List<Map<String, Object>> fixtures, long categoryId) throws Exception {
		List<JsonNode> products = new ArrayList<>();
		for (Map<String, Object> fixture : fixtures) {
			Map<String, Object> request = new LinkedHashMap<>(fixture);
			request.put("categoryId", categoryId);
			products.add(givenProduct(request));
		}
		return products;
	}

	private Map<String, Object> productRequest(long categoryId) {
		Map<String, Object> request = new LinkedHashMap<>(BLACK_SEED_OIL);
		request.put("categoryId", categoryId);
		return request;
	}

	private long givenCategoryId(Map<String, ?> fixture) throws Exception {
		HttpResponse<String> response = send("POST", "/api/admin/categories", json.writeValueAsString(fixture), true, true);
		assertThat(response.statusCode()).isEqualTo(201);
		return json.readTree(response.body()).path("id").asLong();
	}

	private JsonNode givenProduct(Map<String, ?> request) throws Exception {
		HttpResponse<String> response = create(request);
		assertThat(response.statusCode()).isEqualTo(201);
		return json.readTree(response.body());
	}

	private HttpResponse<String> create(Map<String, ?> request) throws Exception {
		return send("POST", "/api/admin/products", json.writeValueAsString(request), true, true);
	}

	private HttpResponse<String> send(String method, String path, String body, boolean authenticated, boolean withCsrf) throws Exception {
		return client.send(method, path, body, authenticated, withCsrf);
	}

	private String csrfToken() throws Exception {
		return client.csrfToken();
	}

	private void signIn() throws Exception {
		client.signIn();
	}

	private void assertProductResponse(JsonNode actual, JsonNode expected) {
		assertThat(actual.size()).isEqualTo(expected.size());
		for (String field : List.of("id", "categoryId")) {
			assertThat(actual.path(field).isIntegralNumber()).as(field).isTrue();
			assertThat(actual.path(field).asLong()).as(field).isEqualTo(expected.path(field).asLong());
		}
		for (String field : List.of("name", "description", "active", "imageUrl", "imageAltText")) {
			assertThat(actual.get(field)).as(field).isEqualTo(expected.get(field));
		}
		assertThat(new BigDecimal(actual.path("price").asText()))
				.isEqualByComparingTo(new BigDecimal(expected.path("price").asText()));
	}

	private void assertInvalid(HttpResponse<String> response, String field) throws Exception {
		assertProblem(response, 400);
		assertThat(json.readTree(response.body()).path("errors").path(field).asText()).isNotBlank();
		assertThat(productCount()).isZero();
	}

	private void assertProblem(HttpResponse<String> response, int status) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		assertThat(json.readTree(response.body()).path("status").asInt()).isEqualTo(status);
	}

	private long productCount() {
		return jdbc.queryForObject("select count(*) from products", Long.class);
	}

}
