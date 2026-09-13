package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

/**
 * 아이디가 없든 비밀번호가 틀리든 같은 예외를 던진다.
 * 응답으로 둘을 구분할 수 있으면 어떤 아이디가 가입돼 있는지 알아낼 수 있다.
 * 어느 쪽이었는지는 logMessage 로만 남긴다.
 */
class LoginFailedException(logMessage: String) : BusinessException(UserErrorCode.LOGIN_FAILED, logMessage)
