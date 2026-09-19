package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

// ADJUSTMENT_AMOUNT_INVALID 와 ADJUSTMENT_REASON_BLANK 둘 다에 쓰이므로 코드를 생성자로 받는다.
class InvalidAdjustmentException(
    code: WalletErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)
