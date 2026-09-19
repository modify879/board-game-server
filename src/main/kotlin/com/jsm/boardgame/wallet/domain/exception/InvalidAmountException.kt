package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidAmountException(
    code: WalletErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)
