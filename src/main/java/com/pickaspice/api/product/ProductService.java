package com.pickaspice.api.product;

import java.math.BigDecimal;
import java.util.List;

import com.pickaspice.api.category.CategoryRepository;
import com.pickaspice.api.error.BusinessException;
import com.pickaspice.api.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProductService {

	private final ProductRepository repository;
	private final CategoryRepository categoryRepository;

	@Transactional(readOnly = true)
	public Page<Product> list(int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by("id").ascending());
		// JPA accepts only signed 32-bit offsets, although PageRequest calculates a long offset.
		if (request.getOffset() > Integer.MAX_VALUE) {
			throw new BusinessException(ErrorCode.PAGE_OFFSET_TOO_LARGE);
		}
		return repository.findAll(request);
	}

	@Transactional(readOnly = true)
	public Product getById(Long id) {
		return repository.findById(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
	}

	@Transactional
	public Product create(String name, String description, BigDecimal price, Long categoryId, Boolean active,
			String imageUrl, String imageAltText) {
		lockCategory(categoryId);
		return save(new Product(name, description, price, categoryId, active == null || active, imageUrl, imageAltText));
	}

	@Transactional
	public Product update(Long id, String name, String description, BigDecimal price, Long categoryId, boolean active,
			String imageUrl, String imageAltText) {
		lockCategory(categoryId);
		Product product = getForUpdate(id);
		product.update(name, description, price, categoryId, active, imageUrl, imageAltText);
		return save(product);
	}

	@Transactional
	public void delete(Long id) {
		repository.delete(getForUpdate(id));
	}

	private Product getForUpdate(Long id) {
		// Serialize updates and deletes so a concurrent deletion is reported as a missing product.
		return repository.findForUpdateById(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
	}

	private void lockCategory(Long categoryId) {
		// Lock the destination before changing a product so category deletion cannot race with either write.
		if (categoryRepository.findByIdInOrderByIdAsc(List.of(categoryId)).isEmpty()) {
			throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND);
		}
	}

	private Product save(Product product) {
		try {
			return repository.saveAndFlush(product);
		}
		catch (DataIntegrityViolationException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "products_name_unique".equals(violation.getConstraintName())) {
					throw new BusinessException(ErrorCode.PRODUCT_NAME_EXISTS, exception);
				}
			}
			throw exception;
		}
	}

}
