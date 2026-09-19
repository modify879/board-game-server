package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidWithdrawalAmountException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WITHDRAWAL_AMOUNT_INVALID, logMessage)
