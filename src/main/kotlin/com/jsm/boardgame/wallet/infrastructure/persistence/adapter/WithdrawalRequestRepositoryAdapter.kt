package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WithdrawalRequestJpaRepository
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.toDomain
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.toJpaEntity
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.stereotype.Repository

@Repository
class WithdrawalRequestRepositoryAdapter(
    private val jpa: WithdrawalRequestJpaRepository,
) : WithdrawalRequestRepository {

    /** 명령 경로 전용 잠금 조회다 — `WithdrawalRequestJpaRepository.findByIdForUpdate` 참고. */
    override fun findById(id: WithdrawalRequestId): WithdrawalRequest? =
        jpa.findByIdForUpdate(id.value)?.toDomain()

    override fun save(request: WithdrawalRequest): WithdrawalRequest =
        jpa.save(request.toJpaEntity()).toDomain()
}
