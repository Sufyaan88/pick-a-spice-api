package com.pickaspice.api.category;

import com.pickaspice.api.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/categories")
@RequiredArgsConstructor
public class CategoryController {

	private final CategoryService service;

	@GetMapping
	public PageResponse<CategoryResponse> list(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		Page<Category> categories = service.list(page, size);
		return new PageResponse<>(categories.getContent().stream().map(CategoryResponse::from).toList(),
				categories.getNumber(), categories.getSize(), categories.getTotalElements());
	}

	@GetMapping("/{id}")
	public CategoryResponse getById(@PathVariable Long id) {
		return CategoryResponse.from(service.getById(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
		Category category = service.create(request.name(), request.description(), request.active(), request.parentId());
		return CategoryResponse.from(category);
	}

	@PutMapping("/{id}")
	public CategoryResponse update(@PathVariable Long id,
			@Validated(CategoryRequest.Update.class) @RequestBody CategoryRequest request) {
		Category category = service.update(id, request.name(), request.description(), request.active(), request.parentId());
		return CategoryResponse.from(category);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

}
