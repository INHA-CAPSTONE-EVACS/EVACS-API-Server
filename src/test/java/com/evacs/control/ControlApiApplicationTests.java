package com.evacs.control;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** 컨텍스트 로딩 + Flyway 마이그레이션 + JPA 스키마 검증(ddl-auto: validate)이 통과하는지 본다. */
@SpringBootTest
@ActiveProfiles("test")
class ControlApiApplicationTests {

	@Test
	void contextLoads() {
	}
}
