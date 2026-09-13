package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class UserNotFoundException(
    logMessage: String,
) : BusinessException(UserErrorCode.USER_NOT_FOUND, logMessage)
