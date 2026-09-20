package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
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

/** 관리자가 지갑 잔액을 직접 지급·회수한다. 지갑이 없으면 lazy 로 만든다. */
@Service
@Transactional
class AdjustWalletBalanceService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val userExistence: UserExistence,
    private val clock: Clock,
) : AdjustWalletBalanceUseCase {

    override fun adjust(command: AdjustWalletBalanceCommand) {
        val adjustment = Adjustment.of(command.amount, command.reason)

        // 이 확인은 친절한 오류용이고 보장이 아니다 — 확인과 저장 사이에 회원 탈퇴가 들어오면
        // 그대로 뚫린다. 실제 보장은 wallets.user_id 외래키(fk_wallets_user, data.sql)다 —
        // "유일성은 DB 가 보장한다" 와 같은 두 겹 구조이고, 사전 체크와 FK 위반 둘 다
        // WALLET_OWNER_NOT_FOUND 로 떨어진다.
        if (!userExistence.exists(command.targetUserId)) {
            throw WalletOwnerNotFoundException(
                "조정 대상 사용자가 없음: targetUserId=${command.targetUserId}",
            )
        }

        val wallet = wallets.findOrOpen(command.targetUserId)
        val entry = wallet.record(
            type = adjustment.type,
            amount = adjustment.amount,
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, command.adminUserId),
            memo = adjustment.reason,
            at = Instant.now(clock),
        )
        wallets.save(wallet)
        ledger.save(entry)
    }
}
