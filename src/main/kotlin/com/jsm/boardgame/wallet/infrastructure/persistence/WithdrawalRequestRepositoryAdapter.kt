package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.exception.WithdrawalRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequest
import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId
import com.jsm.boardgame.wallet.domain.repository.WithdrawalRequestRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Repository

@Repository
class WithdrawalRequestRepositoryAdapter(
    private val jpa: WithdrawalRequestJpaRepository,
) : WithdrawalRequestRepository {

    override fun findById(id: WithdrawalRequestId): WithdrawalRequest? =
        jpa.findById(id.value).map { it.toDomain() }.orElse(null)

    /**
     * `save` 대신 `saveAndFlush` 를 쓴다. `DepositRequestRepositoryAdapter` 와 같은 이유다 —
     * `save` 만 쓰면 UPDATE 의 `@Version` 충돌이 트랜잭션 커밋 시점까지 미뤄져 이 어댑터의
     * `catch` 를 지나쳐 버리고, 이중 처리 방어의 두 번째 겹(DB 낙관적 락)이 통째로 죽는다.
     */
    override fun save(request: WithdrawalRequest): WithdrawalRequest =
        try {
            jpa.saveAndFlush(request.toJpaEntity()).toDomain()
        } catch (e: OptimisticLockingFailureException) {
            throw WithdrawalRequestAlreadyProcessedException("낙관적 락 충돌: ${e.message}")
        }
}
