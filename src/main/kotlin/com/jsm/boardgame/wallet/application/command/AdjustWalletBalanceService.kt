package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.exception.InvalidAdjustmentException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.MONEY_UNIT
import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.repository.LedgerEntryRepository
import com.jsm.boardgame.wallet.domain.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import kotlin.math.abs

/** 관리자가 지갑 잔액을 직접 지급·회수한다. 지갑이 없으면 lazy 로 만든다. */
@Service
@Transactional
class AdjustWalletBalanceService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : AdjustWalletBalanceUseCase {

    override fun adjust(command: AdjustWalletBalanceCommand) {
        if (command.amount == 0L) {
            throw InvalidAdjustmentException(
                WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID,
                "조정 금액이 0임: targetUserId=${command.targetUserId}",
            )
        }
        val absoluteAmount = Money.of(abs(command.amount))
        if (!absoluteAmount.isMultipleOf(MONEY_UNIT)) {
            throw InvalidAdjustmentException(
                WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID,
                "조정 금액이 ${MONEY_UNIT}원 단위가 아님: targetUserId=${command.targetUserId}",
            )
        }
        if (command.reason.isBlank()) {
            throw InvalidAdjustmentException(
                WalletErrorCode.ADJUSTMENT_REASON_BLANK,
                "조정 사유가 비어 있음: targetUserId=${command.targetUserId}",
            )
        }

        val now = Instant.now(clock)
        val type = if (command.amount > 0) LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT else LedgerEntryType.ADMIN_ADJUSTMENT_DEBIT
        val wallet = wallets.findByUserId(command.targetUserId) ?: wallets.save(Wallet.open(command.targetUserId))

        val entry = wallet.record(
            type = type,
            amount = absoluteAmount,
            reference = LedgerReference(LedgerReferenceType.ADMIN_ADJUSTMENT, command.adminUserId),
            memo = command.reason,
            at = now,
        )
        wallets.save(wallet)
        ledger.save(entry)
    }
}
