package com.qrorder.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * PostgreSQL 16 thật cho integration test (SRS §12: Testcontainers, schema tạo bằng Flyway).
 * @ServiceConnection tự cấu hình spring.datasource.*; mỗi Spring context dùng một container.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> =
        PostgreSQLContainer(DockerImageName.parse(POSTGRES_IMAGE))

    companion object {
        const val POSTGRES_IMAGE = "postgres:16-alpine"
    }
}
