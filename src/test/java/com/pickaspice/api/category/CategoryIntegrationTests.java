package com.pickaspice.api.category;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.pickaspice.api.TestDatabaseConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static com.pickaspice.api.category.CategoryFixtures.INACTIVE_INCENSE;
import static com.pickaspice.api.category.CategoryFixtures.PACKAGING;
import static com.pickaspice.api.category.CategoryFixtures.PAGINATION_CATEGORIES;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.security.user.name=category-test-user",
		"spring.security.user.password=category-test-password"
})
@Import(TestDatabaseConfiguration.class)
class CategoryIntegrationTests {

	private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper json;

	private HttpClient client;

	@BeforeEach
	void prepareRequestClientAndDatabase() {
		client = HttpClient.newBuilder()
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.connectTimeout(Duration.ofSeconds(10)).build();
		jdbc.update("delete from categories");
	}

	@AfterEach
	void cleanDatabaseAndCloseRequestClient() {
		try {
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
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.header("Accept", "application/json");
		if (withCsrf) {
			request.header("X-CSRF-TOKEN", csrfToken());
		}
		if (authenticated) {
			String credentials = Base64.getEncoder().encodeToString(
					"category-test-user:category-test-password".getBytes(StandardCharsets.UTF_8));
			request.header("Authorization", "Basic " + credentials);
		}
		return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private String csrfToken() throws Exception {
		// Use Spring Security's real default login page until dedicated admin security is implemented.
		HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/login"))
				.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(200);
		Matcher token = CSRF_INPUT.matcher(login.body());
		assertThat(token.find()).as("Default login page supplies a CSRF token").isTrue();
		return token.group(1);
	}

	private void signIn() throws Exception {
		String form = "username=category-test-user&password=category-test-password&_csrf="
				+ URLEncoder.encode(csrfToken(), StandardCharsets.UTF_8);
		HttpResponse<String> login = client.send(HttpRequest.newBuilder(uri("/login"))
				.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
		assertThat(login.statusCode()).isEqualTo(302);
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
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
