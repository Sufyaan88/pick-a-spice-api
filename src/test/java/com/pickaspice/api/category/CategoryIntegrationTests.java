package com.pickaspice.api.category;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.pickaspice.api.ApiTestClient;
import com.pickaspice.api.TestDatabaseConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static com.pickaspice.api.category.CategoryFixtures.EXISTING_CATEGORY;
import static com.pickaspice.api.category.CategoryFixtures.HERBAL_OILS;
import static com.pickaspice.api.category.CategoryFixtures.HERBAL_PRODUCTS;
import static com.pickaspice.api.category.CategoryFixtures.INACTIVE_INCENSE;
import static com.pickaspice.api.category.CategoryFixtures.PACKAGING;
import static com.pickaspice.api.category.CategoryFixtures.PAGINATION_CATEGORIES;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.security.user.name=category-test-user",
		"spring.security.user.password=category-test-password"
})
@Import(TestDatabaseConfiguration.class)
class CategoryIntegrationTests {

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
	void createsCategoryAndCommitsItToPostgres() throws Exception {
		// Arrange
		Map<String, Object> request = HERBAL_OILS;

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("id").asLong()).isPositive();
		assertThat(body.get("name").asText()).isEqualTo("Herbal Oils");
		assertThat(body.get("description").asText()).isEqualTo("Natural oils");
		assertThat(body.get("active").asBoolean()).isTrue();
		Map<String, Object> saved = jdbc.queryForMap("select * from categories where id = ?",
				body.get("id").asLong());
		assertThat(saved).containsEntry("name", "Herbal Oils")
				.containsEntry("description", "Natural oils").containsEntry("active", true);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void defaultsToActiveWithNoDescription() throws Exception {
		// Arrange
		Map<String, Object> request = PACKAGING;

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("active").asBoolean()).isTrue();
		assertThat(body.get("description").isNull()).isTrue();
		assertThat(jdbc.queryForMap("select description, active from categories"))
				.containsEntry("description", null).containsEntry("active", true);
	}

	@Test
	void allowsAnInactiveCategory() throws Exception {
		// Arrange
		Map<String, Object> request = INACTIVE_INCENSE;

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json.readTree(response.body()).get("active").asBoolean()).isFalse();
		assertThat(jdbc.queryForObject("select active from categories", Boolean.class)).isFalse();
	}

	@Test
	void trimsNameBeforeSavingAndValidatingLength() throws Exception {
		// Arrange
		String name = "a".repeat(100);
		Map<String, Object> request = Map.of("name", "  " + name + "  ");

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json.readTree(response.body()).get("name").asText()).isEqualTo(name);
		assertThat(jdbc.queryForObject("select name from categories", String.class)).isEqualTo(name);
	}

	@Test
	void acceptsMaximumDescriptionLength() throws Exception {
		// Arrange
		Map<String, Object> request = Map.of("name", "Packaging", "description", "a".repeat(1000));

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select length(description) from categories", Integer.class)).isEqualTo(1000);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n"})
	void rejectsMissingOrBlankName(String name) throws Exception {
		// Arrange
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("name", name);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "name");
	}

	@Test
	void rejectsOmittedName() throws Exception {
		// Arrange
		Map<String, Object> request = Map.of();

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "name");
	}

	@Test
	void rejectsNameLongerThan100Characters() throws Exception {
		// Arrange
		Map<String, Object> request = Map.of("name", "a".repeat(101));

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "name");
	}

	@Test
	void rejectsDescriptionLongerThan1000Characters() throws Exception {
		// Arrange
		Map<String, Object> request = Map.of("name", "Packaging", "description", "a".repeat(1001));

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertInvalid(response, "description");
	}

	@ParameterizedTest
	@ValueSource(strings = {"Herbal Oils", "herbal oils", " HERBAL OILS "})
	void rejectsDuplicateNamesWithoutChangingExistingCategory(String duplicate) throws Exception {
		// Arrange
		givenCategory(EXISTING_CATEGORY);
		Map<String, Object> request = Map.of("name", duplicate);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertProblem(response, 409);
		assertThat(json.readTree(response.body()).get("detail").asText())
				.isEqualTo("A category with this name already exists.");
		assertThat(categoryCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select description from categories", String.class)).isEqualTo("Original");
	}

	@Test
	void rejectsMalformedJsonWithoutSaving() throws Exception {
		// Arrange
		String requestBody = "{";

		// Act
		HttpResponse<String> response = post(requestBody, true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(categoryCount()).isZero();
	}

	@Test
	void rejectsAnonymousCreation() throws Exception {
		// Arrange
		String requestBody = json.writeValueAsString(PACKAGING);

		// Act
		HttpResponse<String> response = post(requestBody, false, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(categoryCount()).isZero();
	}

	@Test
	void rejectsCreationWithoutCsrfToken() throws Exception {
		// Arrange
		signIn();
		String requestBody = json.writeValueAsString(PACKAGING);

		// Act
		HttpResponse<String> response = post(requestBody, true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(categoryCount()).isZero();
	}

	@Test
	void updatesCategoryAndCommitsItToPostgres() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> request = Map.of("name", "  Packaging  ", "description", "Bags and containers", "active", false);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("name").asText()).isEqualTo("Packaging");
		assertThat(body.get("description").asText()).isEqualTo("Bags and containers");
		assertThat(body.get("active").asBoolean()).isFalse();
		assertThat(savedCategory(id)).containsEntry("name", "Packaging")
				.containsEntry("description", "Bags and containers").containsEntry("active", false);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"Herbal Oils", "herbal oils", " HERBAL OILS "})
	void allowsKeepingOwnCategoryName(String name) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> request = Map.of("name", name, "description", "Updated", "active", true);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(savedCategory(id)).containsEntry("name", name.strip()).containsEntry("description", "Updated");
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"name\":\"Herbal Oils\",\"active\":true}",
			"{\"name\":\"Herbal Oils\",\"description\":null,\"active\":true}"})
	void clearsOmittedOrNullDescription(String requestBody) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/categories/" + id, requestBody, true, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).get("description").isNull()).isTrue();
		assertThat(savedCategory(id)).containsEntry("description", null);
	}

	@Test
	void reactivatesCategory() throws Exception {
		// Arrange
		long id = givenCategoryId(INACTIVE_INCENSE);
		Map<String, Object> request = Map.of("name", "Incense", "active", true);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).get("active").asBoolean()).isTrue();
		assertThat(savedCategory(id)).containsEntry("active", true);
	}

	@Test
	void acceptsMaximumFieldLengthsOnUpdate() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> request = Map.of("name", "  " + "a".repeat(100) + "  ",
				"description", "b".repeat(1000), "active", true);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(savedCategory(id)).containsEntry("name", "a".repeat(100))
				.containsEntry("description", "b".repeat(1000));
	}

	@Test
	void rejectsUpdatingMissingCategory() throws Exception {
		// Arrange
		long missingId = 987654321L;
		Map<String, Object> request = Map.of("name", "Packaging", "active", true);

		// Act
		HttpResponse<String> response = update(missingId, request);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).get("detail").asText()).isEqualTo("Category not found.");
		assertThat(categoryCount()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"Packaging", "packaging", " PACKAGING "})
	void rejectsUpdatingToAnotherCategoryNameWithoutChangingEitherCategory(String name) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		givenCategory(PACKAGING);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = Map.of("name", name, "description", "Changed", "active", false);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertProblem(response, 409);
		assertThat(json.readTree(response.body()).get("detail").asText())
				.isEqualTo("A category with this name already exists.");
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"name\":null,\"active\":true}",
			"{\"name\":\"\",\"active\":true}", "{\"name\":\"  \",\"active\":true}",
			"{\"name\":\"Updated\"}", "{\"name\":\"Updated\",\"active\":null}", "{"})
	void rejectsInvalidUpdatesWithoutChangingStoredData(String requestBody) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/categories/" + id, requestBody, true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"name", "description"})
	void rejectsExcessiveFieldLengthsOnUpdate(String field) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		Map<String, Object> request = new LinkedHashMap<>(Map.of("name", "Updated", "active", false));
		request.put(field, "a".repeat(field.equals("name") ? 101 : 1001));

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertProblem(response, 400);
		assertThat(json.readTree(response.body()).path("errors").path(field).asText()).isNotBlank();
		assertThat(savedCategory(id)).isEqualTo(before);
	}

	@Test
	void rejectsAnonymousUpdate() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		String requestBody = "{\"name\":\"Changed\",\"active\":false}";

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/categories/" + id, requestBody, false, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(savedCategory(id)).isEqualTo(before);
	}

	@Test
	void rejectsUpdateWithoutCsrfToken() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		signIn();
		String requestBody = "{\"name\":\"Changed\",\"active\":false}";

		// Act
		HttpResponse<String> response = send("PUT", "/api/admin/categories/" + id, requestBody, true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(savedCategory(id)).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void readsCategoryByIdIncludingInactiveCategories(boolean active) throws Exception {
		// Arrange
		givenCategory(PACKAGING);
		Map<String, Object> fixture = new LinkedHashMap<>(HERBAL_OILS);
		fixture.put("active", active);
		long id = givenCategoryId(fixture);
		var before = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/categories/" + id, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("name").asText()).isEqualTo("Herbal Oils");
		assertThat(body.get("description").asText()).isEqualTo("Natural oils");
		assertThat(body.get("active").asBoolean()).isEqualTo(active);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void returnsNotFoundWhenReadingMissingCategory() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		long missingId = id + 1;

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/categories/" + missingId, "", true, false);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).get("detail").asText()).isEqualTo("Category not found.");
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void rejectsReadingWithNonNumericCategoryId() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		String path = "/api/admin/categories/not-a-number";

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertProblem(response, 400);
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void rejectsAnonymousCategoryRead() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/categories/" + id, "", false, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).doesNotContain("Herbal Oils", "Original");
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void listsEmptyCategoryPageWithDefaults() throws Exception {
		// Arrange
		String path = "/api/admin/categories";

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("items").isArray()).isTrue();
		assertThat(body.get("items").size()).isZero();
		assertThat(body.get("page").asInt()).isZero();
		assertThat(body.get("size").asInt()).isEqualTo(20);
		assertThat(body.get("totalCount").asLong()).isZero();
		assertThat(categoryCount()).isZero();
	}

	@Test
	void limitsDefaultCategoryPageTo20Items() throws Exception {
		// Arrange
		List<JsonNode> expected = givenCategories(CategoryFixtures.numberedCategories(21));

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/categories", "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("items")).isEqualTo(json.valueToTree(expected.subList(0, 20)));
		assertThat(body.get("page").asInt()).isZero();
		assertThat(body.get("size").asInt()).isEqualTo(20);
		assertThat(body.get("totalCount").asLong()).isEqualTo(21);
		assertThat(categoryCount()).isEqualTo(21);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void listsCategoriesByIdAcrossPagesIncludingInactive(int page) throws Exception {
		// Arrange
		List<JsonNode> expected = givenCategories(PAGINATION_CATEGORIES);
		var before = jdbc.queryForList("select * from categories order by id");
		String path = "/api/admin/categories?page=" + page + "&size=2";

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		int start = Math.min(page * 2, expected.size());
		int end = Math.min(start + 2, expected.size());
		assertThat(body.get("items")).isEqualTo(json.valueToTree(expected.subList(start, end)));
		assertThat(body.get("page").asInt()).isEqualTo(page);
		assertThat(body.get("size").asInt()).isEqualTo(2);
		assertThat(body.get("totalCount").asLong()).isEqualTo(3);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 100})
	void acceptsCategoryPageSizeBoundaries(int size) throws Exception {
		// Arrange
		String path = "/api/admin/categories?size=" + size;

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("size").asInt()).isEqualTo(size);
		assertThat(body.get("items").size()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=-1", "size=0", "size=-1", "size=101", "page=abc", "size=abc",
			"page=2147483648", "size=2147483648"})
	void rejectsInvalidCategoryPaginationWithoutChangingData(String query) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		String path = "/api/admin/categories?" + query;

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertProblem(response, 400);
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=2147483647&size=100", "page=2147483647&size=2",
			"page=2147483647", "page=1073741824&size=2"})
	void rejectsCategoryPageOffsetsBeyondJpaLimit(String query) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		String path = "/api/admin/categories?" + query;

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertProblem(response, 400);
		assertThat(json.readTree(response.body()).get("detail").asText())
				.isEqualTo("Requested page offset is too large.");
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"page=2147483647&size=1", "page=1073741823&size=2"})
	void acceptsCategoryPageOffsetsWithinJpaLimit(String query) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		String path = "/api/admin/categories?" + query;

		// Act
		HttpResponse<String> response = send("GET", path, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("items").isArray()).isTrue();
		assertThat(body.get("items").size()).isZero();
		assertThat(body.get("totalCount").asLong()).isEqualTo(1);
		assertThat(savedCategory(id)).isEqualTo(before);
	}

	@Test
	void rejectsAnonymousCategoryList() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);

		// Act
		HttpResponse<String> response = send("GET", "/api/admin/categories", "", false, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).doesNotContain("Herbal Oils", "Original");
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void permanentlyDeletesCategoryAndLeavesOtherCategoriesUnchanged(boolean active) throws Exception {
		// Arrange
		long id = givenCategoryId(active ? EXISTING_CATEGORY : INACTIVE_INCENSE);
		long otherId = givenCategoryId(PACKAGING);
		Map<String, Object> otherBefore = savedCategory(otherId);

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + id, "", true, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from categories where id = ?", Long.class, id)).isZero();
		assertThat(savedCategory(otherId)).isEqualTo(otherBefore);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void rejectsDeletingMissingCategoryWithoutChangingExistingData() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		long missingId = id + 1;

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + missingId, "", true, true);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).get("detail").asText()).isEqualTo("Category not found.");
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-a-number", "9223372036854775808"})
	void rejectsDeletingWithInvalidCategoryId(String invalidId) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		String path = "/api/admin/categories/" + invalidId;

		// Act
		HttpResponse<String> response = send("DELETE", path, "", true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void rejectsAnonymousCategoryDeletion() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + id, "", false, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@Test
	void rejectsCategoryDeletionWithoutCsrfToken() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		signIn();

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + id, "", true, false);

		// Assert
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(savedCategory(id)).isEqualTo(before);
		assertThat(categoryCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void createsTopLevelCategoryWithOmittedOrNullParent(boolean explicitNull) throws Exception {
		// Arrange
		Map<String, Object> request = new LinkedHashMap<>(PACKAGING);
		if (explicitNull) {
			request.put("parentId", null);
		}

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json.readTree(response.body()).path("parentId").isNull()).isTrue();
		assertThat(jdbc.queryForMap("select * from categories")).containsEntry("parent_id", null);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void createsSubcategoryUnderActiveOrInactiveParent(boolean parentActive) throws Exception {
		// Arrange
		Map<String, Object> parent = new LinkedHashMap<>(HERBAL_PRODUCTS);
		parent.put("active", parentActive);
		long parentId = givenCategoryId(parent);
		Map<String, Object> parentBefore = savedCategory(parentId);
		Map<String, Object> request = withParent(HERBAL_OILS, parentId);

		// Act
		HttpResponse<String> response = create(request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(201);
		JsonNode body = json.readTree(response.body());
		assertThat(body.path("parentId").asLong()).isEqualTo(parentId);
		assertThat(body.get("active").asBoolean()).isTrue();
		assertThat(savedCategory(body.get("id").asLong())).containsEntry("parent_id", parentId);
		assertThat(savedCategory(parentId)).isEqualTo(parentBefore);
	}

	@ParameterizedTest
	@ValueSource(strings = {"POST", "PUT"})
	void rejectsMissingParentWithoutChangingData(String method) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = withParent(Map.of("name", "Changed", "active", false), id + 1);
		String path = "/api/admin/categories" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 404);
		assertThat(json.readTree(response.body()).get("detail").asText()).isEqualTo("Category not found.");
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"POST", "PUT"})
	void rejectsUsingSubcategoryAsParentWithoutChangingData(String method) throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long childId = givenCategoryId(withParent(HERBAL_OILS, parentId));
		long id = givenCategoryId(PACKAGING);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = withParent(Map.of("name", "Changed", "active", false), childId);
		String path = "/api/admin/categories" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void rejectsCategoryAsOwnParentWithoutChangingData() throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		Map<String, Object> before = savedCategory(id);
		Map<String, Object> request = withParent(Map.of("name", "Changed", "active", false), id);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertProblem(response, 400);
		assertThat(savedCategory(id)).isEqualTo(before);
	}

	@Test
	void rejectsMovingCategoryWithChildrenUnderAnotherParent() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		givenCategory(withParent(HERBAL_OILS, parentId));
		long otherId = givenCategoryId(PACKAGING);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = withParent(Map.of("name", "Changed", "active", false), otherId);

		// Act
		HttpResponse<String> response = update(parentId, request);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void rejectsMovingParentUnderItsOwnChild() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long childId = givenCategoryId(withParent(HERBAL_OILS, parentId));
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = withParent(Map.of("name", "Changed", "active", false), childId);

		// Act
		HttpResponse<String> response = update(parentId, request);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void assignsParentToExistingTopLevelCategory() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long id = givenCategoryId(HERBAL_OILS);
		Map<String, Object> request = withParent(Map.of("name", "Herbal Oils", "active", true), parentId);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("parentId").asLong()).isEqualTo(parentId);
		assertThat(savedCategory(id)).containsEntry("parent_id", parentId);
	}

	@Test
	void movesSubcategoryToAnotherTopLevelParent() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long id = givenCategoryId(withParent(HERBAL_OILS, parentId));
		long otherId = givenCategoryId(PACKAGING);
		Map<String, Object> request = withParent(Map.of("name", "Herbal Oils", "active", true), otherId);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("parentId").asLong()).isEqualTo(otherId);
		assertThat(savedCategory(id)).containsEntry("parent_id", otherId);
		assertThat(savedCategory(parentId)).containsEntry("parent_id", null);
	}

	@Test
	void allowsKeepingSubcategoryParentOnUpdate() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long id = givenCategoryId(withParent(HERBAL_OILS, parentId));
		Map<String, Object> request = withParent(Map.of("name", "Updated Oils", "active", false), parentId);

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("parentId").asLong()).isEqualTo(parentId);
		assertThat(savedCategory(id)).containsEntry("parent_id", parentId)
				.containsEntry("name", "Updated Oils").containsEntry("active", false);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void clearsOmittedOrNullParentOnUpdate(boolean explicitNull) throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long id = givenCategoryId(withParent(HERBAL_OILS, parentId));
		Map<String, Object> request = new LinkedHashMap<>(Map.of("name", "Herbal Oils", "active", true));
		if (explicitNull) {
			request.put("parentId", null);
		}

		// Act
		HttpResponse<String> response = update(id, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).path("parentId").isNull()).isTrue();
		assertThat(savedCategory(id)).containsEntry("parent_id", null);
	}

	@Test
	void allowsEditingParentWithoutChangingChildActivity() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long childId = givenCategoryId(withParent(HERBAL_OILS, parentId));
		Map<String, Object> childBefore = savedCategory(childId);
		Map<String, Object> request = Map.of("name", "Updated Herbal Products", "active", false);

		// Act
		HttpResponse<String> response = update(parentId, request);

		// Assert
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(savedCategory(parentId)).containsEntry("active", false).containsEntry("parent_id", null);
		assertThat(savedCategory(childId)).isEqualTo(childBefore);
	}

	@Test
	void readsParentIdInCategoryAndPaginatedList() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long childId = givenCategoryId(withParent(HERBAL_OILS, parentId));
		var before = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> read = send("GET", "/api/admin/categories/" + childId, "", true, false);
		HttpResponse<String> list = send("GET", "/api/admin/categories", "", true, false);

		// Assert
		assertThat(read.statusCode()).isEqualTo(200);
		assertThat(json.readTree(read.body()).path("parentId").asLong()).isEqualTo(parentId);
		assertThat(list.statusCode()).isEqualTo(200);
		JsonNode items = json.readTree(list.body()).get("items");
		assertThat(items.size()).isEqualTo(2);
		assertThat(items.get(0).path("parentId").isNull()).isTrue();
		assertThat(items.get(1).path("parentId").asLong()).isEqualTo(parentId);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void rejectsDeletingParentWithSubcategories() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		givenCategory(withParent(INACTIVE_INCENSE, parentId));
		var before = jdbc.queryForList("select * from categories order by id");

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + parentId, "", true, true);

		// Assert
		assertProblem(response, 409);
		assertThat(json.readTree(response.body()).get("detail").asText())
				.isEqualTo("A category with subcategories cannot be deleted.");
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void deletesSubcategoryWithoutDeletingItsParent() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long childId = givenCategoryId(withParent(HERBAL_OILS, parentId));
		Map<String, Object> parentBefore = savedCategory(parentId);

		// Act
		HttpResponse<String> response = send("DELETE", "/api/admin/categories/" + childId, "", true, true);

		// Assert
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
		assertThat(categoryCount()).isEqualTo(1);
		assertThat(savedCategory(parentId)).isEqualTo(parentBefore);
	}

	@ParameterizedTest
	@ValueSource(strings = {"POST", "PUT"})
	void keepsCategoryNamesUniqueAcrossParents(String method) throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		givenCategory(withParent(HERBAL_OILS, parentId));
		long otherId = givenCategoryId(PACKAGING);
		long id = givenCategoryId(INACTIVE_INCENSE);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = withParent(Map.of("name", " HERBAL OILS ", "active", true), otherId);
		String path = "/api/admin/categories" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 409);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = {"POST", "PUT"})
	void rejectsNonNumericParentWithoutChangingData(String method) throws Exception {
		// Arrange
		long id = givenCategoryId(EXISTING_CATEGORY);
		var before = jdbc.queryForList("select * from categories order by id");
		String request = "{\"name\":\"Changed\",\"active\":true,\"parentId\":\"invalid\"}";
		String path = "/api/admin/categories" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, request, true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
	}

	@Test
	void concurrentChildCreationAndParentMoveKeepOnlyOneSubcategoryLevel() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long otherId = givenCategoryId(PACKAGING);
		Map<String, Object> child = withParent(HERBAL_OILS, parentId);
		Map<String, Object> move = withParent(Map.of("name", "Herbal Products", "active", true), otherId);
		csrfToken();

		// Act
		List<HttpResponse<String>> responses;
		try (var requests = Executors.newFixedThreadPool(2)) {
			var results = requests.<HttpResponse<String>>invokeAll(List.of(() -> create(child), () -> update(parentId, move)));
			responses = List.of(results.get(0).get(), results.get(1).get());
		}

		// Assert
		List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();
		assertThat(statuses).isIn(List.of(201, 400), List.of(400, 200));
		assertThat(jdbc.queryForObject("""
				select count(*) from categories child join categories parent on child.parent_id = parent.id
				where parent.parent_id is not null
				""", Long.class)).isZero();
	}

	@Test
	void concurrentParentDeletionAndChildCreationDoNotLeaveOrphans() throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		Map<String, Object> child = withParent(HERBAL_OILS, parentId);
		csrfToken();

		// Act
		List<HttpResponse<String>> responses;
		try (var requests = Executors.newFixedThreadPool(2)) {
			var results = requests.<HttpResponse<String>>invokeAll(List.of(() -> create(child),
					() -> send("DELETE", "/api/admin/categories/" + parentId, "", true, true)));
			responses = List.of(results.get(0).get(), results.get(1).get());
		}

		// Assert
		List<Integer> statuses = responses.stream().map(HttpResponse::statusCode).toList();
		assertThat(statuses).isIn(List.of(201, 409), List.of(404, 204));
		assertThat(jdbc.queryForObject("""
				select count(*) from categories child left join categories parent on child.parent_id = parent.id
				where child.parent_id is not null and parent.id is null
				""", Long.class)).isZero();
	}

	@Test
	void parentMigrationPreservesExistingCategoryData() {
		try {
			// Arrange
			Flyway.configure().dataSource(jdbc.getDataSource()).schemas("category_upgrade_test")
					.defaultSchema("category_upgrade_test").target("1").load().migrate();
			jdbc.update("""
					insert into category_upgrade_test.categories (name, description, active)
					values (?, ?, ?)
					""", EXISTING_CATEGORY.get("name"), EXISTING_CATEGORY.get("description"), false);
			Map<String, Object> before = jdbc.queryForMap("select * from category_upgrade_test.categories");

			// Act
			Flyway.configure().dataSource(jdbc.getDataSource()).schemas("category_upgrade_test")
					.defaultSchema("category_upgrade_test").load().migrate();

			// Assert
			assertThat(jdbc.queryForMap("select * from category_upgrade_test.categories"))
					.containsAllEntriesOf(before).containsEntry("parent_id", null);
		}
		finally {
			jdbc.execute("drop schema if exists category_upgrade_test cascade");
		}
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
	void rejectsNonIntegerJsonParentIdWithoutChangingData(String method, String idTemplate) throws Exception {
		// Arrange
		long parentId = givenCategoryId(HERBAL_PRODUCTS);
		long id = givenCategoryId(HERBAL_OILS);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = new LinkedHashMap<>(Map.of("name", "Changed Category", "active", true));
		request.put("parentId", json.readTree(idTemplate.formatted(parentId)));
		String path = "/api/admin/categories" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
		assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isZero();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			POST | 1
			PUT  | 1
			POST | 0
			PUT  | 0
			POST | "true"
			PUT  | "false"
			POST | ""
			PUT  | ""
			""")
	void rejectsNonBooleanJsonActiveWithoutChangingData(String method, String activeJson) throws Exception {
		// Arrange
		long id = givenCategoryId(HERBAL_OILS);
		var before = jdbc.queryForList("select * from categories order by id");
		Map<String, Object> request = new LinkedHashMap<>(Map.of("name", "Changed Category"));
		request.put("active", json.readTree(activeJson));
		String path = "/api/admin/categories" + (method.equals("PUT") ? "/" + id : "");

		// Act
		HttpResponse<String> response = send(method, path, json.writeValueAsString(request), true, true);

		// Assert
		assertProblem(response, 400);
		assertThat(jdbc.queryForList("select * from categories order by id")).isEqualTo(before);
		assertThat(jdbc.queryForObject("select count(*) from products", Long.class)).isZero();
	}

	private Map<String, Object> withParent(Map<String, ?> fixture, long parentId) {
		Map<String, Object> request = new LinkedHashMap<>(fixture);
		request.put("parentId", parentId);
		return request;
	}

	private JsonNode givenCategory(Map<String, ?> fixture) throws Exception {
		HttpResponse<String> response = create(fixture);
		assertThat(response.statusCode()).isEqualTo(201);
		return json.readTree(response.body());
	}

	private long givenCategoryId(Map<String, ?> fixture) throws Exception {
		return givenCategory(fixture).get("id").asLong();
	}

	private List<JsonNode> givenCategories(List<Map<String, Object>> fixtures) throws Exception {
		List<JsonNode> categories = new ArrayList<>();
		for (Map<String, Object> fixture : fixtures) {
			categories.add(givenCategory(fixture));
		}
		return categories;
	}

	private Map<String, Object> savedCategory(long id) {
		return jdbc.queryForMap("select * from categories where id = ?", id);
	}

	private HttpResponse<String> update(long id, Map<String, ?> request) throws Exception {
		return send("PUT", "/api/admin/categories/" + id, json.writeValueAsString(request), true, true);
	}

	private HttpResponse<String> create(Map<String, ?> request) throws Exception {
		return post(json.writeValueAsString(request), true, true);
	}

	private HttpResponse<String> post(String body, boolean authenticated, boolean withCsrf) throws Exception {
		return send("POST", "/api/admin/categories", body, authenticated, withCsrf);
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

	private void assertInvalid(HttpResponse<String> response, String field) throws Exception {
		assertProblem(response, 400);
		assertThat(json.readTree(response.body()).path("errors").path(field).asText()).isNotBlank();
		assertThat(categoryCount()).isZero();
	}

	private void assertProblem(HttpResponse<String> response, int status) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow())
				.startsWith("application/problem+json");
		assertThat(json.readTree(response.body()).get("status").asInt()).isEqualTo(status);
	}

	private long categoryCount() {
		return jdbc.queryForObject("select count(*) from categories", Long.class);
	}

}
