package com.realtimecollab.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimitPropertiesTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("app.auth.rate-limit.requests=20", "app.auth.rate-limit.window-seconds=60",
                    "app.auth.rate-limit.max-clients=10000");

    @ParameterizedTest
    @CsvSource({"requests,0", "requests,1001", "window-seconds,0", "window-seconds,3601",
            "max-clients,0", "max-clients,100001"})
    void invalidSettingsFailAtStartup(String property, String value) {
        context.withPropertyValues("app.auth.rate-limit." + property + "=" + value)
                .run(application -> assertThat(application).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AuthRateLimitProperties.class)
    static class PropertiesConfiguration {}
}
