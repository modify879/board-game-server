package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidUsernameException(
    logMessage: String,
) : BusinessException(UserErrorCode.USERNAME_FORMAT, logMessage)
