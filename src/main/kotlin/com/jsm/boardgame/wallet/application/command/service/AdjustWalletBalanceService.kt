package com.jsm.boardgame.wallet.application.command.service

import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceUseCase
import com.jsm.boardgame.wallet.application.command.usecase.AdjustWalletBalanceCommand
import com.jsm.boardgame.wallet.application.port.UserExistence
import com.jsm.boardgame.wallet.domain.exception.InvalidAdjustmentException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import com.jsm.boardgame.wallet.domain.exception.WalletOwnerNotFoundException
import com.jsm.boardgame.wallet.domain.model.LedgerEntryType
import com.jsm.boardgame.wallet.domain.model.LedgerReference
import com.jsm.boardgame.wallet.domain.model.LedgerReferenceType
import com.jsm.boardgame.wallet.domain.model.MONEY_UNIT
import com.jsm.boardgame.wallet.domain.model.Money
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
        val type = if (command.amount > 0) LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT else LedgerEntryType.ADMIN_ADJUSTMENT_DEBIT
        val wallet = wallets.findOrOpen(command.targetUserId)

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
