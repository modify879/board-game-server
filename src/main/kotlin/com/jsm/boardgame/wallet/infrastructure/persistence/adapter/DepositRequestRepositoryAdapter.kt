package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.wallet.infrastructure.persistence.entity.DepositRequestJpaRepository
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.toDomain
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.toJpaEntity
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.stereotype.Repository

@Repository
class DepositRequestRepositoryAdapter(
    private val jpa: DepositRequestJpaRepository,
) : DepositRequestRepository {

    /** 명령 경로 전용 잠금 조회다 — `DepositRequestJpaRepository.findByIdForUpdate` 참고. */
    override fun findById(id: DepositRequestId): DepositRequest? =
        jpa.findByIdForUpdate(id.value)?.toDomain()

    override fun save(request: DepositRequest): DepositRequest =
        jpa.save(request.toJpaEntity()).toDomain()
}
