package com.pickaspice.api.product;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidProductImage.Validator.class)
public @interface ValidProductImage {

	String message() default "Invalid product image metadata.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<ValidProductImage, ProductRequest> {

		@Override
		public boolean isValid(ProductRequest request, ConstraintValidatorContext context) {
			if (request == null) {
				return true;
			}
			if (request.imageUrl() != null && !isHttpsUrl(request.imageUrl())) {
				context.disableDefaultConstraintViolation();
				context.buildConstraintViolationWithTemplate("Image URL must be a valid HTTPS URL.")
						.addPropertyNode("imageUrl").addConstraintViolation();
				return false;
			}
			if (request.imageAltText() != null && request.imageUrl() == null) {
				context.disableDefaultConstraintViolation();
				context.buildConstraintViolationWithTemplate("Image alternative text requires an image URL.")
						.addPropertyNode("imageAltText").addConstraintViolation();
				return false;
			}
			return true;
		}

		private boolean isHttpsUrl(String value) {
			try {
				URI uri = URI.create(value);
				return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getPort() <= 65535;
			}
			catch (IllegalArgumentException exception) {
				return false;
			}
		}

	}

}
