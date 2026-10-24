package com.pickaspice.api.error;

public enum ErrorCode {

	CATEGORY_NAME_EXISTS("A category with this name already exists."),
	CATEGORY_NOT_FOUND("Category not found."),
	PAGE_OFFSET_TOO_LARGE("Requested page offset is too large.");

	private final String message;

	ErrorCode(String message) {
		this.message = message;
	}

	public String message() {
		return message;
	}

}
