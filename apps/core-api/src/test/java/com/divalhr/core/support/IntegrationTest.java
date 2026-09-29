package com.divalhr.core.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** Full application context against a real PostgreSQL container. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    properties = {
      "springdoc.api-docs.enabled=true",
      "divalhr.environment=test",
      "divalhr.pagination.cursor-signing-key=test-only-cursor-signing-key-0000000000000001"
    })
@AutoConfigureMockMvc
@Import({TestSecurityConfig.class, PostgresContainerConfig.class})
public @interface IntegrationTest {}
