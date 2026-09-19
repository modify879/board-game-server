package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class WalletNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WALLET_NOT_FOUND, logMessage)
