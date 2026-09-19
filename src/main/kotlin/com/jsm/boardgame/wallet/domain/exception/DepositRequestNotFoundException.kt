package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class DepositRequestNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.DEPOSIT_REQUEST_NOT_FOUND, logMessage)
