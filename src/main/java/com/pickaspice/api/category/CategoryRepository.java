package com.pickaspice.api.category;

import java.util.Collection;
import java.util.List;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	boolean existsByParentId(Long parentId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	List<Category> findByIdInOrderByIdAsc(Collection<Long> ids);

}
