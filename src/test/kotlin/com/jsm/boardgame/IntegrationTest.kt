package com.jsm.boardgame

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import

/**
 * 모든 통합 테스트가 이 애너테이션으로 컨텍스트 하나를 공유한다.
 *
 * 프로퍼티·프로필·중첩 `@TestConfiguration`·`@DynamicPropertySource` 처럼 컨텍스트 키를
 * 바꾸는 것을 덧붙이면 컨텍스트가 하나 더 캐시되고 그 컨텍스트가 자기 커넥션 풀을 쥔다 —
 * 빈을 갈아끼워야 할 때만 쓴다. 테스트 전체에 걸친 값은 build.gradle.kts 의 `systemProperty` 로 준다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
annotation class IntegrationTest
