package com.realtimecollab.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    void testProfileDoesNotImportCloudDatabaseCredentials() {
        var environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgresql://cloud:secret@example.invalid/cloud")
                .withProperty("spring.datasource.url", "jdbc:h2:mem:isolated");
        environment.setActiveProfiles("test");

        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:isolated");
        assertThat(environment.getPropertySources().contains("cloudDatabaseUrl")).isFalse();
    }

    @Test
    void applicationStillImportsCloudDatabaseUrl() {
        var environment = new MockEnvironment()
                .withProperty("DATABASE_URL", "postgresql://cloud:secret@example.invalid/cloud");

        new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://example.invalid/cloud");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("cloud");
    }

    @Test
    void convertsPostgresUrlToSpringDatasourceProperties() {
        Map<String, Object> properties = DatabaseUrlEnvironmentPostProcessor
                .toSpringDatasourceProperties(
                        "postgresql://neondb_owner:p%40ssword@example.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require")
                .orElseThrow();

        assertThat(properties)
                .containsEntry("spring.datasource.url",
                        "jdbc:postgresql://example.ap-southeast-1.aws.neon.tech/neondb?sslmode=require")
                .containsEntry("spring.datasource.username", "neondb_owner")
                .containsEntry("spring.datasource.password", "p@ssword");
    }

    @Test
    void leavesJdbcUrlForApplicationProperties() {
        assertThat(DatabaseUrlEnvironmentPostProcessor
                .toSpringDatasourceProperties("jdbc:postgresql://localhost:5432/collabdb"))
                .isEmpty();
    }
}
