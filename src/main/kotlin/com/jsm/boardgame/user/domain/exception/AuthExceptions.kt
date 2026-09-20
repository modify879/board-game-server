package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.error.BusinessException

/**
 * RawPassword(길이 미달·초과)와 PasswordHash(빈 값) 검증 실패를 대표하는 예외.
 * 실패 사유에 따라 다른 UserErrorCode 를 싣는다 (PASSWORD_TOO_SHORT / PASSWORD_TOO_LONG).
 */
class InvalidPasswordException(
    code: UserErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

/** 아이디 없음·비밀번호 틀림을 구분하지 않는다. 어느 쪽이었는지는 logMessage 로만 남긴다. */
class LoginFailedException(logMessage: String) : BusinessException(UserErrorCode.LOGIN_FAILED, logMessage)

/**
 * 만료·위조·재사용을 모두 이 예외로 다룬다.
 * 재사용 탐지 사실을 응답으로 알리면 공격자가 탈취가 들켰는지 알게 되므로 로그로만 구분한다.
 */
class InvalidRefreshTokenException(logMessage: String) :
    BusinessException(UserErrorCode.REFRESH_TOKEN_INVALID, logMessage)
