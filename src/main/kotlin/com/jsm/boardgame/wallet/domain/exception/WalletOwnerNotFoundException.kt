package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class WalletOwnerNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WALLET_OWNER_NOT_FOUND, logMessage)
