package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class DepositRequestAlreadyProcessedException(
    logMessage: String,
) : BusinessException(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, logMessage)
