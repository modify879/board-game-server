package com.jsm.boardgame.common.config

import com.jsm.boardgame.BoardGameApplication
import com.jsm.boardgame.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.postgresql.PostgreSQLContainer

class QaProfileGuardMissingMarkerIntegrationTest {

    @Test
    fun `표식이 없는 DB 에서는 qa 프로필 컨텍스트가 기동에 실패한다`() {
        PostgreSQLContainer("postgres:18-alpine").use { unmarked ->
            unmarked.start()
            val redis = TestcontainersConfiguration.redis
            val builder = SpringApplicationBuilder(BoardGameApplication::class.java)
                .profiles("qa")
                .web(WebApplicationType.NONE)
                .properties(
                    "spring.docker.compose.enabled=false",
                    "spring.datasource.url=${unmarked.jdbcUrl}",
                    "spring.datasource.username=${unmarked.username}",
                    "spring.datasource.password=${unmarked.password}",
                    "spring.data.redis.host=${redis.host}",
                    "spring.data.redis.port=${redis.firstMappedPort}",
                )

            val failure = runCatching { builder.run().close() }.exceptionOrNull()

            assertThat(failure).isNotNull()
            val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
            assertThat(messages).contains("app_environment 표식이 qa 인 DB")
        }
    }
}
