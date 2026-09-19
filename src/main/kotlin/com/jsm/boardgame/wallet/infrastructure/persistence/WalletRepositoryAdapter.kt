package com.jsm.boardgame.wallet.infrastructure.persistence

import com.jsm.boardgame.common.support.violatedConstraint
import com.jsm.boardgame.wallet.domain.exception.ConcurrentWalletUpdateException
import com.jsm.boardgame.wallet.domain.exception.InsufficientBalanceException
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Repository

private const val CONSTRAINT_BALANCE_NON_NEGATIVE = "ck_wallets_balance_non_negative"
private const val CONSTRAINT_USER = "uk_wallets_user"

@Repository
class WalletRepositoryAdapter(
    private val jpa: WalletJpaRepository,
) : WalletRepository {

    override fun findByUserId(userId: Long): Wallet? =
        jpa.findByUserId(userId)?.toDomain()

    /**
     * `save` 대신 `saveAndFlush` 를 쓴다. `save` 만 쓰면 UPDATE 는 트랜잭션 커밋 시점에야
     * flush 되므로, CHECK 제약 위반과 `@Version` 충돌이 이 어댑터의 `catch` 를 지나쳐 버리고
     * 도메인 예외 번역이 통째로 죽는다. (신규 INSERT 는 IDENTITY 채번 때문에 우연히 즉시
     * 나가지만 UPDATE 는 아니다 — 그래서 "충전은 되는데 잔액 음수 방지가 안 잡히는" 형태로만 드러난다.)
     */
    override fun save(wallet: Wallet): Wallet =
        try {
            jpa.saveAndFlush(wallet.toJpaEntity()).toDomain()
        } catch (e: DataIntegrityViolationException) {
            throw translate(e)
        } catch (e: OptimisticLockingFailureException) {
            throw ConcurrentWalletUpdateException("낙관적 락 충돌: ${e.message}")
        }

    private fun translate(e: DataIntegrityViolationException): RuntimeException {
        val constraintName = e.violatedConstraint(CONSTRAINT_BALANCE_NON_NEGATIVE, CONSTRAINT_USER) ?: return e
        return when (constraintName) {
            CONSTRAINT_BALANCE_NON_NEGATIVE -> InsufficientBalanceException("CHECK 제약 위반: $constraintName")
            CONSTRAINT_USER -> ConcurrentWalletUpdateException("unique 제약 위반: $constraintName")
            else -> e
        }
    }
}
