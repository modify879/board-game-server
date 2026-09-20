package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidAdjustmentException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode

/**
 * 관리자가 잔액을 직접 지급·회수할 때의 금액과 사유.
 *
 * 조정은 충전·환전과 달리 애그리거트가 없어서 이 규칙들이 서비스의 `if` 로 흩어져 있었다.
 * 같은 종류의 규칙을 `DepositRequest.request()` 는 도메인 팩토리에서 지키는데 조정만
 * 응용 계층에서 지키는 비대칭이었다.
 *
 * **부호를 받는 유일한 도메인 타입이다.** `Money` 는 음수를 가질 수 없고 방향은
 * [LedgerEntryType] 이 나르므로(원장 합산으로 잔액을 검증할 수 있어야 한다),
 * 부호 있는 원시 Long 이 도메인 안으로 들어오는 자리를 여기 하나로 좁힌다.
 */
class Adjustment private constructor(
    val type: LedgerEntryType,
    val amount: Money,
    val reason: String,
) {
    companion object {
        fun of(signedAmount: Long, reason: String): Adjustment {
            // 단위 검사가 절댓값 변환보다 먼저다. Long.MIN_VALUE 는 절댓값이 Long 으로 표현되지
            // 않는 유일한 값이라, 순서를 뒤집으면 ADJUSTMENT_AMOUNT_INVALID(400) 가 아니라
            // ArithmeticException(500) 으로 새어 나간다.
            if (signedAmount == 0L || signedAmount % MONEY_UNIT != 0L) {
                throw InvalidAdjustmentException(
                    WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID,
                    "조정 금액이 유효하지 않음: amount=$signedAmount",
                )
            }
            val normalizedReason = reason.trim()
            if (normalizedReason.isEmpty()) {
                throw InvalidAdjustmentException(WalletErrorCode.ADJUSTMENT_REASON_BLANK, "조정 사유가 비어 있음")
            }

            // absExact 는 표현할 수 없는 값에서 조용히 음수를 돌려주지 않고 터진다.
            // 위 단위 검사가 Long.MIN_VALUE 를 이미 걸러내므로 여기까지 오지 않지만,
            // MONEY_UNIT 이 바뀌어도 조용히 음수가 흘러들지 않게 하는 보험이다.
            val type =
                if (signedAmount > 0) LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT else LedgerEntryType.ADMIN_ADJUSTMENT_DEBIT
            return Adjustment(type, Money.of(Math.absExact(signedAmount)), normalizedReason)
        }
    }
}
