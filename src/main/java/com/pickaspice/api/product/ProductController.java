package com.pickaspice.api.product;

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
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductController {

	private final ProductService service;

	@GetMapping
	public PageResponse<ProductResponse> list(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		Page<Product> products = service.list(page, size);
		return new PageResponse<>(products.getContent().stream().map(ProductResponse::from).toList(),
				products.getNumber(), products.getSize(), products.getTotalElements());
	}

	@GetMapping("/{id}")
	public ProductResponse getById(@PathVariable Long id) {
		return ProductResponse.from(service.getById(id));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProductResponse create(@Valid @RequestBody ProductRequest request) {
		Product product = service.create(request.name(), request.description(), request.price(), request.categoryId(),
				request.active(), request.imageUrl(), request.imageAltText());
		return ProductResponse.from(product);
	}

	@PutMapping("/{id}")
	public ProductResponse update(@PathVariable Long id,
			@Validated(ProductRequest.Update.class) @RequestBody ProductRequest request) {
		Product product = service.update(id, request.name(), request.description(), request.price(), request.categoryId(),
				request.active(), request.imageUrl(), request.imageAltText());
		return ProductResponse.from(product);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

}
