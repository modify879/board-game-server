package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

/**
 * RawPassword(길이 미달·초과)와 PasswordHash(빈 값) 검증 실패를 대표하는 예외.
 * 실패 사유에 따라 다른 UserErrorCode 를 싣는다 (PASSWORD_TOO_SHORT / PASSWORD_TOO_LONG).
 */
class InvalidPasswordException(
    code: UserErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)
