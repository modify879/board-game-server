package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InsufficientBalanceException(
    logMessage: String,
) : BusinessException(WalletErrorCode.INSUFFICIENT_BALANCE, logMessage)

class ConcurrentWalletUpdateException(
    logMessage: String,
) : BusinessException(WalletErrorCode.CONCURRENT_WALLET_UPDATE, logMessage)

class WalletOwnerNotFoundException(
    logMessage: String,
) : BusinessException(WalletErrorCode.WALLET_OWNER_NOT_FOUND, logMessage)

class InvalidAmountException(
    code: WalletErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

// ADJUSTMENT_AMOUNT_INVALID 와 ADJUSTMENT_REASON_BLANK 둘 다에 쓰이므로 코드를 생성자로 받는다.
class InvalidAdjustmentException(
    code: WalletErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

class InvalidRejectionReasonException(
    logMessage: String,
) : BusinessException(WalletErrorCode.REJECTION_REASON_BLANK, logMessage)

class NotRequestOwnerException(
    logMessage: String,
) : BusinessException(WalletErrorCode.NOT_REQUEST_OWNER, logMessage)
