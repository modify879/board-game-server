package com.jsm.boardgame.wallet.application.command

import com.jsm.boardgame.wallet.domain.model.WithdrawalRequestId

interface RequestWithdrawalUseCase {
    fun request(command: RequestWithdrawalCommand): WithdrawalRequestId
}
