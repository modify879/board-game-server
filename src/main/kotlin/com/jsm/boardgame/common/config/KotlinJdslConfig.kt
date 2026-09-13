package com.jsm.boardgame.common.config

import com.linecorp.kotlinjdsl.render.jpql.JpqlRenderContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Kotlin JDSL 로 JPQL 을 렌더링할 때 쓰는 [JpqlRenderContext] 를 명시적으로 등록한다.
 *
 * `spring-data-jpa-boot4-support` 는 `@ConditionalOnMissingBean` 으로 같은 타입의
 * 자동 설정 빈을 이미 제공하지만, 기술 설정은 `common/config` 에 명시적으로 두는
 * 이 프로젝트의 관례를 따르기 위해 직접 정의한다. 직렬화기·인트로스펙터를
 * 커스터마이징할 필요가 생기면 여기서 `registerModule` 로 등록한다.
 */
@Configuration
class KotlinJdslConfig {

    @Bean
    fun jpqlRenderContext(): JpqlRenderContext = JpqlRenderContext()
}
