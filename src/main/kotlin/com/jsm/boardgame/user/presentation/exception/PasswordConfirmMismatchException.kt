package com.jsm.boardgame.user.presentation.exception

import com.jsm.boardgame.common.support.BusinessException
import com.jsm.boardgame.user.domain.exception.UserErrorCode

/**
 * "비밀번호 확인"은 입력 폼의 관심사지 도메인 불변식이 아니다.
 * 도메인 User 는 확인 비밀번호라는 개념 자체를 모른다. 그래서 이 예외는 presentation 이 소유한다.
 */
class PasswordConfirmMismatchException(logMessage: String) :
    BusinessException(UserErrorCode.PASSWORD_CONFIRM_MISMATCH, logMessage)
