package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidDepositAmountException(
    logMessage: String,
) : BusinessException(WalletErrorCode.DEPOSIT_AMOUNT_INVALID, logMessage)
