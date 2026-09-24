package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.exception.IdempotencyKeyInvalidException
import com.jsm.boardgame.wallet.application.port.AdjustmentKeyRegistry
import com.jsm.boardgame.wallet.application.port.UserExistence
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.Adjustment
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

private const val IDEMPOTENCY_KEY_MAX_LENGTH = 100

/** 관리자가 지갑 잔액을 직접 지급·회수한다. 지갑이 없으면 lazy 로 만든다. */
@Service
@Transactional
class AdjustWalletBalanceService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val userExistence: UserExistence,
    private val adjustmentKeys: AdjustmentKeyRegistry,
    private val clock: Clock,
) : AdjustWalletBalanceUseCase {

    override fun adjust(command: AdjustWalletBalanceCommand) {
        val adjustment = Adjustment.of(command.amount, command.reason)
        val idempotencyKey = requireValidIdempotencyKey(command.idempotencyKey)

        // 이 확인은 친절한 오류용이고 보장이 아니다 — 확인과 저장 사이에 회원 탈퇴가 들어오면
        // 그대로 뚫린다. 실제 보장은 wallets.user_id 외래키(fk_wallets_user, data.sql)다 —
        // "유일성은 DB 가 보장한다" 와 같은 두 겹 구조이고, 사전 체크와 FK 위반 둘 다
        // WALLET_OWNER_NOT_FOUND 로 떨어진다.
        if (!userExistence.exists(command.targetUserId)) {
            throw WalletOwnerNotFoundException(
                "조정 대상 사용자가 없음: targetUserId=${command.targetUserId}",
            )
        }

        val now = Instant.now(clock)

        // 키를 지갑을 건드리기 전에 먼저 선점한다: 같은 @Transactional 안에서
        // (1) 중복 요청은 돈이 움직이기 전에 막히고, (2) 이 아래에서 무엇이 실패해도 claim 이
        // 함께 롤백돼 재시도가 다시 열리고, (3) 두 요청이 동시에 같은 키로 들어오면
        // 이 INSERT 의 PK 로 직렬화돼 늦게 커밋하는 쪽만 409 를 받는다.
        adjustmentKeys.claim(idempotencyKey, command.adminUserId, command.targetUserId, now)

        val wallet = wallets.findOrOpen(command.targetUserId)
        val entry = wallet.record(
            type = adjustment.type,
            amount = adjustment.amount,
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, command.adminUserId),
            memo = adjustment.reason,
            at = now,
        )
        wallets.save(wallet)
        ledger.save(entry)
    }

    private fun requireValidIdempotencyKey(key: String?): String {
        if (key.isNullOrBlank() || key.codePointCount(0, key.length) > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw IdempotencyKeyInvalidException("Idempotency-Key 가 유효하지 않음: key=$key")
        }
        return key
    }
}
