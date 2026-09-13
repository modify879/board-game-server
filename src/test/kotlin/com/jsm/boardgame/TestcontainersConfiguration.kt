package com.jsm.boardgame

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

/**
 * 테스트에서는 `spring-boot-docker-compose` 가 동작하지 않는다 (developmentOnly).
 * Testcontainers 로 compose.yaml 과 같은 버전의 PostgreSQL 을 직접 띄워 연결한다.
 *
 * 컨테이너를 테스트 클래스마다 새로 띄우지 않기 위해 별도의 리스너/싱글턴 트릭을 쓰지 않는다.
 * `@ServiceConnection` 을 쓰는 테스트 클래스들이 동일한 설정 조합(annotations, @Import 등)을
 * 쓰는 한 스프링 테스트 컨텍스트 캐싱이 컨테이너를 포함한 컨텍스트 자체를 재사용한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:18-alpine")
}
