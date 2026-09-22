package com.jsm.boardgame.common.error

/**
 * 인증은 됐지만 그 사용자에게 허용되지 않은 리소스에 접근했을 때 쓴다.
 * 스프링 시큐리티의 AccessDeniedException(같은 이름, 다른 패키지: org.springframework.security.access)
 * 과는 다른 클래스다. 이름을 `PermissionDeniedException` 으로 구별해서 동명 타입 혼동을 방지한다 —
 * 스프링 것은 REST 필터 체인이 hasRole 실패에 쓰고, 이건 STOMP 구독 인가처럼 필터 체인 밖에서
 * 같은 계약(CommonErrorCode.ACCESS_DENIED)을 지키려는 도메인 예외다.
 */
class PermissionDeniedException(logMessage: String) :
    BusinessException(CommonErrorCode.ACCESS_DENIED, logMessage)
