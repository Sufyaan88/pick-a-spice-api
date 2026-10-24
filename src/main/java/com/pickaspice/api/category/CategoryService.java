package com.pickaspice.api.category;

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
public class CategoryService {

	private final CategoryRepository repository;

	@Transactional(readOnly = true)
	public Page<Category> list(int page, int size) {
		PageRequest request = PageRequest.of(page, size, Sort.by("id").ascending());
		// JPA accepts only signed 32-bit offsets, although PageRequest calculates a long offset.
		if (request.getOffset() > Integer.MAX_VALUE) {
			throw new BusinessException(ErrorCode.PAGE_OFFSET_TOO_LARGE);
		}
		return repository.findAll(request);
	}

	@Transactional(readOnly = true)
	public Category getById(Long id) {
		return repository.findById(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
	}

	@Transactional
	public Category create(String name, String description, Boolean active) {
		Category category = new Category(name, description, active == null || active);
		return save(category);
	}

	@Transactional
	public Category update(Long id, String name, String description, boolean active) {
		Category category = getById(id);
		category.update(name, description, active);
		return save(category);
	}

	@Transactional
	public void delete(Long id) {
		repository.delete(getById(id));
	}

	private Category save(Category category) {
		try {
			return repository.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException exception) {
			for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException violation
						&& "categories_name_unique".equals(violation.getConstraintName())) {
					throw new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, exception);
				}
			}
			throw exception;
		}
	}

}
