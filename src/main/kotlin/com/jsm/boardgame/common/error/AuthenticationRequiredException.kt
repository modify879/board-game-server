package com.jsm.boardgame.common.error

/**
 * 인증 주체(JWT)는 서명 검증을 통과했지만, 그 안의 정보를 신뢰할 수 있는 형태로 쓸 수 없을 때 쓴다.
 * 예: subject 가 사용자 식별자로 파싱되지 않는 경우.
 */
class AuthenticationRequiredException(logMessage: String) :
    BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED, logMessage)
