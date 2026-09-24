package com.jsm.boardgame.wallet.infrastructure.persistence.adapter

import com.jsm.boardgame.common.persistence.violatedConstraint
import com.jsm.boardgame.wallet.application.exception.AdjustmentAlreadyAppliedException
import com.jsm.boardgame.wallet.application.port.AdjustmentKeyRegistry
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WalletAdjustmentKeyJpaEntity
import com.jsm.boardgame.wallet.infrastructure.persistence.entity.WalletAdjustmentKeyJpaRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.time.Instant

private const val CONSTRAINT_PK = "wallet_adjustment_keys_pkey"

@Repository
class AdjustmentKeyRegistryAdapter(
    private val jpa: WalletAdjustmentKeyJpaRepository,
) : AdjustmentKeyRegistry {

    // saveAndFlush 를 쓴다 — WalletRepositoryAdapter 와 같은 이유다. save() 만 쓰면 flush 가
    // 커밋까지 미뤄질 수 있어 PK 위반이 호출자의 @Transactional 커밋 시점에야 터져 이 catch 를 지나친다.
    override fun claim(key: String, adminUserId: Long, targetUserId: Long, at: Instant) {
        try {
            jpa.saveAndFlush(
                WalletAdjustmentKeyJpaEntity(
                    idempotencyKey = key,
                    adminUserId = adminUserId,
                    targetUserId = targetUserId,
                    claimedAt = at,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            val constraintName = e.violatedConstraint(CONSTRAINT_PK) ?: throw e
            throw AdjustmentAlreadyAppliedException("PK 제약 위반: $constraintName")
        }
    }
}
