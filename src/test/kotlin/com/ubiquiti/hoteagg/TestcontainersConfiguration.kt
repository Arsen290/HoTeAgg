package com.ubiquiti.hoteagg

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        // TimescaleDB disabled, plain PostgreSQL is used:
        // DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres")
        return PostgreSQLContainer(DockerImageName.parse("postgres:16"))
    }

}
