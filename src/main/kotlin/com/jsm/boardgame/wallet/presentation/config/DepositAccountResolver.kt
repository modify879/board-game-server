package com.jsm.boardgame.wallet.presentation.config

import com.jsm.boardgame.wallet.presentation.rest.response.DepositAccountResponse
import org.springframework.stereotype.Component

@Component
class DepositAccountResolver(private val properties: DepositAccountProperties) {

    fun resolve(): DepositAccountResponse =
        DepositAccountResponse(
            bankName = properties.bankName,
            accountNumber = properties.accountNumber,
            accountHolder = properties.accountHolder,
        )
}
