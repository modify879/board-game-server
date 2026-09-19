package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class WithdrawalRequestAlreadyProcessedException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, logMessage)
