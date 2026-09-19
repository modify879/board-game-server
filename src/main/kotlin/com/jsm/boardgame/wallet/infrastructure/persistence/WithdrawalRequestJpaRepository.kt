package com.jsm.boardgame.wallet.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface WithdrawalRequestJpaRepository : JpaRepository<WithdrawalRequestJpaEntity, Long>
