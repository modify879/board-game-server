package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InsufficientBalanceException(
    logMessage: String,
) : BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE, logMessage)
