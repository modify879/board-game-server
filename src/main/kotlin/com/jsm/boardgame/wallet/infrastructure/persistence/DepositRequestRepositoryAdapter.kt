package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.wallet.domain.exception.DepositRequestAlreadyProcessedException
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Repository

@Repository
class DepositRequestRepositoryAdapter(
    private val jpa: DepositRequestJpaRepository,
) : DepositRequestRepository {

    override fun findById(id: DepositRequestId): DepositRequest? =
        jpa.findById(id.value).map { it.toDomain() }.orElse(null)

    /**
     * `save` 대신 `saveAndFlush` 를 쓴다. `WalletRepositoryAdapter` 와 같은 이유다 —
     * `save` 만 쓰면 UPDATE 의 `@Version` 충돌이 트랜잭션 커밋 시점까지 미뤄져 이 어댑터의
     * `catch` 를 지나쳐 버리고, 이중 승인 방어의 두 번째 겹(DB 낙관적 락)이 통째로 죽는다.
     */
    override fun save(request: DepositRequest): DepositRequest =
        try {
            jpa.saveAndFlush(request.toJpaEntity()).toDomain()
        } catch (e: OptimisticLockingFailureException) {
            // 새 에러 코드를 만들지 않는다. 동시 승인 경합의 실제 의미는 "이미 처리됨" 이고,
            // 클라이언트는 도메인 상태 검사로 떨어졌는지 DB 락으로 떨어졌는지 구분할 필요가 없다.
            throw DepositRequestAlreadyProcessedException("낙관적 락 충돌: ${e.message}")
        }
}
