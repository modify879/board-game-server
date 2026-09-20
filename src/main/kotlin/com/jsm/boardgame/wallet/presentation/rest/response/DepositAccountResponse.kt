package com.jsm.boardgame.wallet.presentation.rest.response

import com.jsm.boardgame.wallet.presentation.config.DepositAccountProperties

data class DepositAccountResponse(
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
) {
    companion object {
        // 다른 응답 DTO 와 달리 출처가 query/view 가 아니라 설정값이지만, 조립 책임이
        // DTO 에 있다는 점은 같다. 여기만 from() 이 없으면 "응답 DTO 는 from()" 이
        // 규칙이 아니라 경향이 된다.
        fun from(properties: DepositAccountProperties): DepositAccountResponse =
            DepositAccountResponse(
                bankName = properties.bankName,
                accountNumber = properties.accountNumber,
                accountHolder = properties.accountHolder,
            )
    }
}
