package com.pickaspice.api.product;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.groups.Default;

@ValidProductImage
public record ProductRequest(
		@NotBlank(message = "Name is required.")
		@Size(max = 200, message = "Name must be at most 200 characters.") String name,
		@Size(max = 5000, message = "Description must be at most 5000 characters.") String description,
		@NotNull(message = "Price is required.")
		@DecimalMin(value = "0", inclusive = false, message = "Price must be greater than zero.")
		@Digits(integer = 10, fraction = 2, message = "Price must have at most 10 integer digits and 2 decimal places.") BigDecimal price,
		@NotNull(message = "Category is required.") Long categoryId,
		@NotNull(groups = Update.class, message = "Active is required.") Boolean active,
		@Size(max = 2048, message = "Image URL must be at most 2048 characters.") String imageUrl,
		@Size(max = 200, message = "Image alternative text must be at most 200 characters.") String imageAltText) {

	public ProductRequest {
		name = name == null ? null : name.strip();
	}

	// PUT requires active explicitly while retaining every default field and image constraint.
	public interface Update extends Default {
	}

}
