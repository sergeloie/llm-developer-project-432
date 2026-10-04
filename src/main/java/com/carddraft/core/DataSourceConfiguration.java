package com.carddraft.core;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Builds the one connection pool the application uses, from validated settings.
 *
 * <p>Spring Boot's datasource autoconfiguration backs off once a DataSource bean exists, which
 * is what makes {@code card.db.*} the single source of truth rather than a second copy of
 * configuration that could drift from {@code spring.datasource.*}.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfiguration {

    @Bean
    DataSource dataSource(CardDatabaseSettings settings) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("card-pool");
        config.setJdbcUrl(settings.url());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setConnectionTimeout(settings.connectionTimeout().toMillis());
        config.setInitializationFailTimeout(settings.initializationFailTimeoutMillis());
        return new HikariDataSource(config);
    }
}
