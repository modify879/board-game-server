package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

/** 아이디 없음·비밀번호 틀림을 구분하지 않는다. 어느 쪽이었는지는 logMessage 로만 남긴다. */
class LoginFailedException(logMessage: String) : BusinessException(UserErrorCode.LOGIN_FAILED, logMessage)
