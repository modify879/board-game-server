package com.jsm.boardgame

import com.redis.testcontainers.RedisContainer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * `spring-boot-docker-compose` 는 developmentOnly 라 테스트에서는 동작하지 않는다.
 *
 * 컨테이너는 컨텍스트마다가 아니라 JVM 에 한 벌만 띄운다 — companion object 의 `by lazy` 로 최초
 * 접근 시 한 번만 start() 한다. 그래서 스프링 테스트 컨텍스트가 몇 개로 쪼개지든(설정 조합이 달라
 * 캐시가 갈리든) 전부 같은 Postgres·Redis 를 공유한다 — 테스트는 다른 테스트가 남긴 데이터에
 * 기대면 안 된다. `@Bean(destroyMethod = "")` 로 컨텍스트가 캐시에서 축출되며 닫힐 때 스프링이
 * 이 공유 컨테이너를 멈추지 않게 막는다 — 정리는 Testcontainers(Ryuk)가 JVM 종료 시 한다.
 *
 * Redis 컨테이너는 `org.testcontainers:testcontainers-redis` 가 아니라
 * `com.redis:testcontainers-redis` 아티팩트(그룹이 다르다)가 제공하는
 * `com.redis.testcontainers.RedisContainer` 를 쓴다. Spring Boot 의
 * `RedisContainerConnectionDetailsFactory` 가 이 클래스를 인식해 `@ServiceConnection` 을 지원한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean(destroyMethod = "")
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = postgres

    @Bean(destroyMethod = "")
    @ServiceConnection
    fun redisContainer(): RedisContainer = redis

    companion object {
        val postgres: PostgreSQLContainer by lazy {
            PostgreSQLContainer("postgres:18-alpine").apply { start() }
        }
        val redis: RedisContainer by lazy {
            RedisContainer("redis:8-alpine").apply { start() }
        }
    }
}
