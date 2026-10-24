package com.pickaspice.api.category;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

final class CategoryFixtures {

	static final Map<String, Object> EXISTING_CATEGORY = Map.of(
			"name", "Herbal Oils", "description", "Original", "active", true);
	static final Map<String, Object> HERBAL_OILS = Map.of(
			"name", "Herbal Oils", "description", "Natural oils");
	static final Map<String, Object> PACKAGING = Map.of("name", "Packaging");
	static final Map<String, Object> INACTIVE_INCENSE = Map.of("name", "Incense", "active", false);
	static final List<Map<String, Object>> PAGINATION_CATEGORIES = List.of(
			Map.of("name", "Packaging", "description", "Packaging description", "active", true),
			Map.of("name", "Herbal Oils", "description", "Herbal Oils description", "active", false),
			Map.of("name", "Incense", "description", "Incense description", "active", true));

	private CategoryFixtures() {
	}

	static List<Map<String, Object>> numberedCategories(int count) {
		return IntStream.range(0, count)
				.mapToObj(index -> Map.<String, Object>of("name", "Category " + index)).toList();
	}

}
