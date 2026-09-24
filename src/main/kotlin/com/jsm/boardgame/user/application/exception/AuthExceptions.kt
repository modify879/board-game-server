package com.jsm.boardgame.user.application.exception

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.user.domain.exception.UserErrorCode

/**
 * 인증 실패는 도메인 불변식이 아니라 유스케이스의 결과다. `User` 애그리거트에는 세션도 토큰도
 * 로그인도 없고(`domain/model` 전체에 그 단어가 없다), 이 예외들을 던지는 곳도 전부
 * `application/command/service` 다. 포트를 `application/port` 에 둔 것과 같은 기준이다.
 *
 * 에러 코드는 [UserErrorCode] 를 계속 쓴다 — 코드 enum 은 계층이 아니라 **컨텍스트당 하나**다.
 * 계층별로 쪼개면 클라이언트가 보는 계약이 우리 패키지 구조를 따라 흔들린다.
 */
class LoginFailedException(logMessage: String) : BusinessException(UserErrorCode.LOGIN_FAILED, logMessage)

/**
 * 로그인 실패가 짧은 시간 안에 누적되면 계정을 영구히 잠근다. `User.lockedAt`(DB)이 실제 잠금
 * 상태이고, 이 예외는 그 상태를 401 로 드러낼 뿐이다. 관리자의 `POST /api/admin/users/{id}/unlock`
 * 로만 풀린다.
 */
class AccountLockedException(logMessage: String) : BusinessException(UserErrorCode.ACCOUNT_LOCKED, logMessage)

/**
 * 만료·위조·재사용을 모두 이 예외로 다룬다.
 * 재사용 탐지 사실을 응답으로 알리면 공격자가 탈취가 들켰는지 알게 되므로 로그로만 구분한다.
 */
class InvalidRefreshTokenException(logMessage: String) :
    BusinessException(UserErrorCode.REFRESH_TOKEN_INVALID, logMessage)
