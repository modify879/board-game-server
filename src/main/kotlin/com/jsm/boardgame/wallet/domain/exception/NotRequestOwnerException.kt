package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class NotRequestOwnerException(
    logMessage: String,
) : BusinessException(WalletErrorCode.NOT_REQUEST_OWNER, logMessage)
