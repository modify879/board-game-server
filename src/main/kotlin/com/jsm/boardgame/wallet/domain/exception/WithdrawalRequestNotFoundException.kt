package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class WithdrawalRequestNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND, logMessage)
