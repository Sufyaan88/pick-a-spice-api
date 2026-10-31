package com.pickaspice.api.category;

public record CategoryResponse(Long id, String name, String description, boolean active, Long parentId) {

	static CategoryResponse from(Category category) {
		return new CategoryResponse(category.getId(), category.getName(), category.getDescription(),
				category.isActive(), category.getParentId());
	}

}
