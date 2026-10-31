package com.pickaspice.api.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

final class ProductFixtures {

	static final Map<String, Object> BLACK_SEED_OIL = Map.of(
			"name", "Black Seed Oil",
			"description", "100 ml bottle",
			"price", new BigDecimal("45.00"),
			"imageUrl", "https://images.example.test/black-seed-oil.jpg",
			"imageAltText", "Black Seed Oil in a 100 ml bottle");
	static final List<Map<String, Object>> PAGINATION_PRODUCTS = List.of(
			BLACK_SEED_OIL,
			Map.of("name", "Packaging Bags", "price", new BigDecimal("10.00"), "active", true),
			Map.of("name", "Incense Sticks", "price", new BigDecimal("25.00"), "active", false));

	private ProductFixtures() {
	}

	static List<Map<String, Object>> numberedProducts(int count) {
		return IntStream.range(0, count).mapToObj(index -> Map.<String, Object>of(
				"name", "Product " + index, "price", new BigDecimal("10.00"))).toList();
	}

}
