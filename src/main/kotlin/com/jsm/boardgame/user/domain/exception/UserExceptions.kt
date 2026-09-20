package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.error.BusinessException

class UserNotFoundException(
    logMessage: String,
) : BusinessException(UserErrorCode.USER_NOT_FOUND, logMessage)

class InvalidNicknameException(
    code: UserErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

class InvalidUsernameException(
    logMessage: String,
) : BusinessException(UserErrorCode.USERNAME_FORMAT, logMessage)

class InvalidProfileImageKeyException(
    logMessage: String,
) : BusinessException(UserErrorCode.PROFILE_IMAGE_KEY_INVALID, logMessage)

class DuplicateUsernameException(
    logMessage: String,
) : BusinessException(UserErrorCode.DUPLICATE_USERNAME, logMessage)

class DuplicateNicknameException(
    logMessage: String,
) : BusinessException(UserErrorCode.DUPLICATE_NICKNAME, logMessage)

class InvalidUserRoleException(
    logMessage: String,
) : BusinessException(UserErrorCode.USER_ROLE_INVALID, logMessage)
