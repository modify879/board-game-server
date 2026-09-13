package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

/**
 * 만료·위조·재사용을 모두 이 예외로 다룬다.
 * 재사용 탐지 사실을 응답으로 알리면 공격자가 탈취가 들켰는지 알게 되므로 로그로만 구분한다.
 */
class InvalidRefreshTokenException(logMessage: String) :
    BusinessException(UserErrorCode.REFRESH_TOKEN_INVALID, logMessage)
