package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidNicknameException(
    code: UserErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)
