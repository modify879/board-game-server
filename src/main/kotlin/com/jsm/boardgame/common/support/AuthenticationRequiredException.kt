package com.jsm.boardgame.common.support

/**
 * 인증 주체(JWT)는 서명 검증을 통과했지만, 그 안의 정보를 신뢰할 수 있는 형태로 쓸 수 없을 때 쓴다.
 * 예: subject 가 사용자 식별자로 파싱되지 않는 경우.
 *
 * 지금 우리 발급기(JwtTokenIssuer)는 항상 숫자 문자열을 subject 로 발급하므로 이 경로는
 * 현재 도달할 수 없다. 그러나 토큰 발급 경로가 늘어나거나 서명 키가 약해져 위조 토큰이
 * 검증을 통과하는 경우를 대비한 방어다 — 그런 토큰이라도 최소한 500 대신 401 + 오류 계약을
 * 지키게 한다.
 */
class AuthenticationRequiredException(logMessage: String) :
    BusinessException(CommonErrorCode.AUTHENTICATION_REQUIRED, logMessage)
