package com.pickaspice.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
public class JsonConfiguration {

	@Bean
	JsonMapperBuilderCustomizer strictIntegerAndBooleanInputs() {
		return builder -> {
			builder.withCoercionConfig(LogicalType.Integer, coercions -> {
				coercions.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
				coercions.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
				coercions.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
			});
			builder.withCoercionConfig(LogicalType.Boolean, coercions -> {
				coercions.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
				coercions.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
				coercions.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
				coercions.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
			});
		};
	}

}
