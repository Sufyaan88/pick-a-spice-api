package com.pickaspice.api.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;

public record CategoryRequest(
		@NotBlank(message = "Name is required.")
		@Size(max = 100, message = "Name must be at most 100 characters.") String name,
		@Size(max = 1000, message = "Description must be at most 1000 characters.") String description,
		@NotNull(groups = Update.class, message = "Active is required.") Boolean active) {

	public CategoryRequest {
		name = name == null ? null : name.strip();
	}

	// Updates require active explicitly; creation keeps its default. Both use the other field constraints.
	public interface Update extends Default {
	}

}
