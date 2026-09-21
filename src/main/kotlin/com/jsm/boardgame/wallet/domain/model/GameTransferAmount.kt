package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidAmountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode

/**
 * 바이인·캐시아웃 금액을 Money 로 만든다. 게임 이체엔 DepositRequest/WithdrawalRequest 같은
 * 영속 애그리거트가 없어 단위 검사를 둘 도메인 팩토리가 없다 — 그래서 함수 하나로 둔다.
 * 음수는 Money.of() 가 AMOUNT_NEGATIVE 로 먼저 거부하므로 그 뒤에 단위만 검사한다.
 * 0 은 단위 검사를 통과해 Wallet.record() 의 AMOUNT_NOT_POSITIVE 로 떨어진다 — 의도된 동작이다.
 */
fun gameTransferMoney(amount: Long): Money {
    val money = Money.of(amount)
    if (amount % MONEY_UNIT != 0L) {
        throw InvalidAmountException(
            WalletErrorCode.GAME_TRANSFER_AMOUNT_INVALID,
            "게임 이체 금액이 유효하지 않음: amount=$amount",
        )
    }
    return money
}
