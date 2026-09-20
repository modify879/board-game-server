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

/** 관리자가 지갑 잔액을 직접 지급·회수한다. 지갑이 없으면 lazy 로 만든다. */
@Service
@Transactional
class AdjustWalletBalanceService(
    private val wallets: WalletRepository,
    private val ledger: LedgerEntryRepository,
    private val clock: Clock,
) : AdjustWalletBalanceUseCase {

    override fun adjust(command: AdjustWalletBalanceCommand) {
        // 단위 검사가 절댓값 변환보다 먼저다. Long.MIN_VALUE 는 절댓값이 Long 으로 표현되지
        // 않는 유일한 값이라, 순서를 뒤집으면 ADJUSTMENT_AMOUNT_INVALID(400) 가 아니라
        // ArithmeticException(500) 으로 새어 나간다.
        if (command.amount == 0L || command.amount % MONEY_UNIT != 0L) {
            throw InvalidAdjustmentException(
                WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID,
                "조정 금액이 유효하지 않음: targetUserId=${command.targetUserId}",
            )
        }
        if (command.reason.isBlank()) {
            throw InvalidAdjustmentException(
                WalletErrorCode.ADJUSTMENT_REASON_BLANK,
                "조정 사유가 비어 있음: targetUserId=${command.targetUserId}",
            )
        }

        // absExact 는 표현할 수 없는 값에서 조용히 음수를 돌려주지 않고 터진다.
        // 위 단위 검사가 Long.MIN_VALUE 를 이미 걸러내므로 여기까지 오지 않지만,
        // MONEY_UNIT 이 바뀌어도 조용히 음수가 흘러들지 않게 하는 보험이다.
        val absoluteAmount = Money.of(Math.absExact(command.amount))

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
