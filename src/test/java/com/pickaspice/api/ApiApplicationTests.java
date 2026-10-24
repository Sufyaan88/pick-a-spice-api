package com.pickaspice.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestDatabaseConfiguration.class)
class ApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
