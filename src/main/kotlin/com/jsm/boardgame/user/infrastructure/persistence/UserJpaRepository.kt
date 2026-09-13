package com.jsm.boardgame.user.infrastructure.persistence

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

// KotlinJdslJpqlExecutor 를 상속하면 Kotlin JDSL 이 findAll/findPage 등의 실행기를
// 커스텀 구현체로 자동 주입한다 (KotlinJdslJpaRepositoryFactoryBeanPostProcessor).
// findProfileById 의 JDSL 프로젝션은 UserQueryRepositoryAdapter 에서 이 실행기로 수행한다.
interface UserJpaRepository :
    JpaRepository<UserJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    fun existsByUsername(username: String): Boolean

    fun existsByNickname(nickname: String): Boolean
}
