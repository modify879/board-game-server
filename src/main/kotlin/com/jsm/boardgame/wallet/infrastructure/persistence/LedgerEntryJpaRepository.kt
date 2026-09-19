package com.jsm.boardgame.wallet.infrastructure.persistence

import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import org.springframework.data.jpa.repository.JpaRepository

interface LedgerEntryJpaRepository :
    JpaRepository<LedgerEntryJpaEntity, Long>,
    KotlinJdslJpqlExecutor
