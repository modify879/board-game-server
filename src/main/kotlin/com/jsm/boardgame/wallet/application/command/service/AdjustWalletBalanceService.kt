package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.support.requireValidIdempotencyKey
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.port.AdjustmentKeyRegistry
import com.jsm.boardgame.wallet.application.port.UserExistence
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.Adjustment
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.UserId
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import com.jsm.boardgame.wallet.application.event.WalletCredited
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/** 관리자가 지갑 잔액을 직접 지급·회수한다. 지갑이 없으면 lazy 로 만든다. */
@Service
@Transactional
class AdjustWalletBalanceService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val userExistence: UserExistence,
    private val adjustmentKeys: AdjustmentKeyRegistry,
    private val clock: Clock,
    private val eventPublisher: ApplicationEventPublisher,
) : AdjustWalletBalanceUseCase {

    override fun adjust(command: AdjustWalletBalanceCommand) {
        val adjustment = Adjustment.of(command.amount, command.reason)
        val idempotencyKey = requireValidIdempotencyKey(command.idempotencyKey)

        val now = Instant.now(clock)

        // 키를 지갑을 건드리기 전에 먼저 선점한다: 같은 @Transactional 안에서
        // (1) 중복 요청은 돈이 움직이기 전에 막히고, (2) 이 아래에서 무엇이 실패해도 claim 이
        // 함께 롤백돼 재시도가 다시 열리고, (3) 두 요청이 동시에 같은 키로 들어오면
        // 이 INSERT 의 PK 로 직렬화돼 늦게 커밋하는 쪽만 409 를 받는다.
        adjustmentKeys.claim(idempotencyKey, UserId(command.adminUserId), UserId(command.targetUserId), now)

        // 활성 확인은 지갑 락을 잡은 뒤에 한다 — 탈퇴가 같은 락으로 직렬화하므로 기다리는 동안 커밋된 탈퇴를 본다.
        // 아예 없는 사용자는 findOrOpen 의 INSERT 가 fk_wallets_user 로 먼저 막는다. 둘 다 WALLET_OWNER_NOT_FOUND 다.
        val wallet = wallets.findOrOpen(UserId(command.targetUserId))
        if (!userExistence.exists(UserId(command.targetUserId))) {
            throw WalletOwnerNotFoundException(
                "조정 대상 사용자가 없음: targetUserId=${command.targetUserId}",
            )
        }
        val entry = wallet.record(
            type = adjustment.type,
            amount = adjustment.amount,
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, command.adminUserId),
            memo = adjustment.reason,
            at = now,
        )
        wallets.save(wallet)
        ledger.save(entry)
        // 증액만 알린다 — 감액은 게임이 합칠 돈이 아니다.
        if (command.amount > 0) eventPublisher.publishEvent(WalletCredited(command.targetUserId))
    }
}
