package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidBankAccountException(
    logMessage: String,
) : BusinessException(WalletErrorCode.BANK_ACCOUNT_INVALID, logMessage)
