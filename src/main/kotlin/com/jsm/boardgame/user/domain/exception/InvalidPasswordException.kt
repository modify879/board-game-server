package com.jsm.boardgame.user.domain.exception

import com.jsm.boardgame.common.support.BusinessException

/**
 * RawPassword(길이 미달)와 PasswordHash(빈 값) 검증 실패를 모두 대표하는 예외.
 * 두 경우 모두 "비밀번호 형식" 문제라는 점은 같으므로 고정된 코드 하나로 처리한다.
 */
class InvalidPasswordException(
    logMessage: String,
) : BusinessException(UserErrorCode.PASSWORD_TOO_SHORT, logMessage)
