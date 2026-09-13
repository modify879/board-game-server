package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class DuplicateUsernameException(
    logMessage: String,
) : BusinessException(UserErrorCode.DUPLICATE_USERNAME, logMessage)
