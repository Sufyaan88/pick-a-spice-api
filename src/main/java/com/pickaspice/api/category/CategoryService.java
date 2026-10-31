package com.pickaspice.api.category;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
	public Category create(String name, String description, Boolean active, Long parentId) {
		List<Category> locked = lockCategories(null, parentId);
		validateParent(null, parentId, locked);
		Category category = new Category(name, description, active == null || active, parentId);
		return save(category);
	}

	@Transactional
	public Category update(Long id, String name, String description, boolean active, Long parentId) {
		List<Category> locked = lockCategories(id, parentId);
		Category category = getLockedCategory(id, locked);
		validateParent(id, parentId, locked);
		category.update(name, description, active, parentId);
		return save(category);
	}

	@Transactional
	public void delete(Long id) {
		Category category = getLockedCategory(id, lockCategories(id, null));
		if (repository.existsByParentId(id)) {
			throw new BusinessException(ErrorCode.CATEGORY_HAS_SUBCATEGORIES);
		}
		try {
			repository.delete(category);
			repository.flush();
		}
		catch (DataIntegrityViolationException exception) {
			throw translatePersistenceFailure(exception);
		}
	}

	private List<Category> lockCategories(Long id, Long parentId) {
		List<Long> ids = Stream.of(id, parentId).filter(Objects::nonNull).distinct().toList();
		// Lock in ID order so concurrent moves and child creation preserve the single-level hierarchy.
		return ids.isEmpty() ? List.of() : repository.findByIdInOrderByIdAsc(ids);
	}

	private Category getLockedCategory(Long id, List<Category> locked) {
		return locked.stream().filter(category -> category.getId().equals(id)).findFirst()
				.orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
	}

	private void validateParent(Long id, Long parentId, List<Category> locked) {
		if (parentId == null) {
			return;
		}
		if (parentId.equals(id)) {
			throw new BusinessException(ErrorCode.CATEGORY_PARENT_INVALID);
		}
		Category parent = getLockedCategory(parentId, locked);
		if (parent.getParentId() != null || (id != null && repository.existsByParentId(id))) {
			throw new BusinessException(ErrorCode.CATEGORY_PARENT_INVALID);
		}
	}

	private Category save(Category category) {
		try {
			return repository.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException exception) {
			throw translatePersistenceFailure(exception);
		}
	}

	private RuntimeException translatePersistenceFailure(DataIntegrityViolationException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				if ("categories_name_unique".equals(violation.getConstraintName())) {
					return new BusinessException(ErrorCode.CATEGORY_NAME_EXISTS, exception);
				}
				if ("products_category_fk".equals(violation.getConstraintName())) {
					return new BusinessException(ErrorCode.CATEGORY_HAS_PRODUCTS, exception);
				}
			}
		}
		return exception;
	}

}
