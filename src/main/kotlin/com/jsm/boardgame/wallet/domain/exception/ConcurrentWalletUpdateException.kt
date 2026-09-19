package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class ConcurrentWalletUpdateException(
    logMessage: String,
) : BusinessException(WalletErrorCode.CONCURRENT_WALLET_UPDATE, logMessage)
