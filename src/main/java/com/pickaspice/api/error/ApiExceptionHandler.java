package com.pickaspice.api.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed.");
		Map<String, String> errors = new LinkedHashMap<>();
		exception.getBindingResult().getFieldErrors()
				.forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
		problem.setProperty("errors", errors);
		return handleExceptionInternal(exception, problem, headers, status, request);
	}

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ProblemDetail> handleBusinessException(BusinessException exception) {
		ErrorCode errorCode = exception.getErrorCode();
		HttpStatus status = switch (errorCode) {
			case CATEGORY_NAME_EXISTS, CATEGORY_HAS_SUBCATEGORIES, CATEGORY_HAS_PRODUCTS, PRODUCT_NAME_EXISTS -> HttpStatus.CONFLICT;
			case CATEGORY_NOT_FOUND, PRODUCT_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case PAGE_OFFSET_TOO_LARGE, CATEGORY_PARENT_INVALID -> HttpStatus.BAD_REQUEST;
		};
		return ResponseEntity.of(ProblemDetail.forStatusAndDetail(status, errorCode.message())).build();
	}

}
