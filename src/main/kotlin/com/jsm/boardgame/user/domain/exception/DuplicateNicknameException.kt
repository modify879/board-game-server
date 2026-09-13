package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class DuplicateNicknameException(
    logMessage: String,
) : BusinessException(UserErrorCode.DUPLICATE_NICKNAME, logMessage)
