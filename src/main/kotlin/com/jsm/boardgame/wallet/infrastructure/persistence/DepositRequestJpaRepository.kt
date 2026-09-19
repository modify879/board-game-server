package com.jsm.boardgame.wallet.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface DepositRequestJpaRepository : JpaRepository<DepositRequestJpaEntity, Long>
