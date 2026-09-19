package com.jsm.boardgame.wallet.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface WalletJpaRepository : JpaRepository<WalletJpaEntity, Long> {
    fun findByUserId(userId: Long): WalletJpaEntity?
}
