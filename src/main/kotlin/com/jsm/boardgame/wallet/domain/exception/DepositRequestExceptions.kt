package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.error.BusinessException

class DepositRequestAlreadyProcessedException(
    logMessage: String,
) : BusinessException(WalletErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED, logMessage)

class DepositRequestNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.DEPOSIT_REQUEST_NOT_FOUND, logMessage)

class InvalidDepositAmountException(
    logMessage: String,
) : BusinessException(WalletErrorCode.DEPOSIT_AMOUNT_INVALID, logMessage)
