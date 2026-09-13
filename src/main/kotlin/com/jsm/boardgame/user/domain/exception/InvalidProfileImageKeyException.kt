package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

class InvalidProfileImageKeyException(
    logMessage: String,
) : BusinessException(UserErrorCode.PROFILE_IMAGE_KEY_INVALID, logMessage)
