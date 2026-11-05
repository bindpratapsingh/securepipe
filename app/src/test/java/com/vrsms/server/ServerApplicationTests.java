package com.vrsms.server;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the whole Spring context, which needs a reachable PostgreSQL
 * instance because the entities map enums to native Postgres ENUM types.
 * Tagged "integration" so the Jenkins unit-test stage skips it; run it
 * locally with: ./mvnw test -Dgroups=integration
 */
@Tag("integration")
@SpringBootTest
class ServerApplicationTests {

	@Test
	void contextLoads() {
	}

}
