package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.wallet.infrastructure.persistence.entity.DepositRequestJpaRepository
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.toDomain
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.toJpaEntity
import com.jsm.boardgame.wallet.domain.model.DepositRequest
import com.jsm.boardgame.wallet.domain.model.DepositRequestId
import com.jsm.boardgame.wallet.domain.repository.DepositRequestRepository
import com.jsm.boardgame.common.persistence.violatedConstraint
import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyConflictException
import com.jsm.boardgame.wallet.domain.model.UserId
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository

private const val CONSTRAINT_USER_KEY = "uk_deposit_requests_user_key"

@Repository
class DepositRequestRepositoryAdapter(
    private val jpa: DepositRequestJpaRepository,
) : DepositRequestRepository {

    /** 명령 경로 전용 잠금 조회다 — `DepositRequestJpaRepository.findByIdForUpdate` 참고. */
    override fun findById(id: DepositRequestId): DepositRequest? =
        jpa.findByIdForUpdate(id.value)?.toDomain()

    override fun findByUserAndIdempotencyKey(userId: UserId, idempotencyKey: String): DepositRequest? =
        jpa.findByUserIdAndIdempotencyKey(userId.value, idempotencyKey)?.toDomain()

    // saveAndFlush 를 쓴다 — save() 만 쓰면 flush 가 커밋까지 미뤄질 수 있어 유니크 위반이
    // 호출자의 @Transactional 커밋 시점에야 터져 이 catch 를 지나친다.
    override fun save(request: DepositRequest): DepositRequest =
        try {
            jpa.saveAndFlush(request.toJpaEntity()).toDomain()
        } catch (e: DataIntegrityViolationException) {
            val constraintName = e.violatedConstraint(CONSTRAINT_USER_KEY) ?: throw e
            throw IdempotencyKeyConflictException("유니크 제약 위반: $constraintName")
        }
}
