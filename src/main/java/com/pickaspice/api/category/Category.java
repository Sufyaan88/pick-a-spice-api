package com.pickaspice.api.category;

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
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(length = 1000)
	private String description;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "parent_id")
	private Long parentId;

	Category(String name, String description, boolean active, Long parentId) {
		this.name = name;
		this.description = description;
		this.active = active;
		this.parentId = parentId;
	}

	void update(String name, String description, boolean active, Long parentId) {
		this.name = name;
		this.description = description;
		this.active = active;
		this.parentId = parentId;
	}

}
