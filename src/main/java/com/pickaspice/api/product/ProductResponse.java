package com.pickaspice.api.product;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price, Long categoryId,
		boolean active, String imageUrl, String imageAltText) {

	static ProductResponse from(Product product) {
		return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice(),
				product.getCategoryId(), product.isActive(), product.getImageUrl(), product.getImageAltText());
	}

}
