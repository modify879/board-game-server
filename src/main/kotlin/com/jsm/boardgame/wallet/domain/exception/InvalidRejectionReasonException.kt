package com.jsm.boardgame.wallet.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidRejectionReasonException(
    logMessage: String,
) : BusinessException(WalletErrorCode.REJECTION_REASON_BLANK, logMessage)
