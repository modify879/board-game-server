package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class WithdrawalRequestAlreadyProcessedException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED, logMessage)

class WithdrawalRequestNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WITHDRAWAL_REQUEST_NOT_FOUND, logMessage)

class InvalidWithdrawalAmountException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WITHDRAWAL_AMOUNT_INVALID, logMessage)

class InvalidBankAccountException(
    logMessage: String,
) : BusinessException(WalletErrorCode.BANK_ACCOUNT_INVALID, logMessage)
