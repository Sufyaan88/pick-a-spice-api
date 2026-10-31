package com.pickaspice.api.product;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 200)
	private String name;

	@Column(length = 5000)
	private String description;

	@Column(nullable = false, precision = 12, scale = 2)
	private BigDecimal price;

	@Column(name = "category_id", nullable = false)
	private Long categoryId;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "image_url", length = 2048)
	private String imageUrl;

	@Column(name = "image_alt_text", length = 200)
	private String imageAltText;

	Product(String name, String description, BigDecimal price, Long categoryId, boolean active,
			String imageUrl, String imageAltText) {
		update(name, description, price, categoryId, active, imageUrl, imageAltText);
	}

	void update(String name, String description, BigDecimal price, Long categoryId, boolean active,
			String imageUrl, String imageAltText) {
		this.name = name;
		this.description = description;
		this.price = price;
		this.categoryId = categoryId;
		this.active = active;
		this.imageUrl = imageUrl;
		this.imageAltText = imageAltText;
	}

}
