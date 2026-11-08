package com.pickaspice.api.error;

public enum ErrorCode {

	CATEGORY_NAME_EXISTS("A category with this name already exists."),
	CATEGORY_NOT_FOUND("Category not found."),
	CATEGORY_PARENT_INVALID("Categories support only one level of subcategories and cannot be their own parent."),
	CATEGORY_HAS_SUBCATEGORIES("A category with subcategories cannot be deleted."),
	CATEGORY_HAS_PRODUCTS("A category with products cannot be deleted."),
	PRODUCT_NAME_EXISTS("A product with this name already exists."),
	PRODUCT_NOT_FOUND("Product not found."),
	PAGE_OFFSET_TOO_LARGE("Requested page offset is too large."),
	ADMIN_EMAIL_EXISTS("An admin account with this email already exists."),
	ADMIN_EMAIL_INVALID("Enter a valid email address of at most 254 characters."),
	ADMIN_PASSWORD_INVALID("Use 15 to 128 characters and avoid blocked passwords."),
	USER_NOT_FOUND("User not found."),
	USER_PAGE_INVALID("Page must be zero or greater, and size must be between 1 and 100.");

	private final String message;

	ErrorCode(String message) {
		this.message = message;
	}

	public String message() {
		return message;
	}

}
