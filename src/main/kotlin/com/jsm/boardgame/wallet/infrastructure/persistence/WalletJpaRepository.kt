package com.jsm.boardgame.wallet.infrastructure.persistence

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

// KotlinJdslJpqlExecutor 를 상속하면 findAll/findPage 등의 실행기가 자동 주입된다 (UserJpaRepository 와 같은 이유).
interface WalletJpaRepository :
    JpaRepository<WalletJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    fun findByUserId(userId: Long): WalletJpaEntity?
}
