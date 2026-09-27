package com.jsm.boardgame.holdem.application.exception

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode

/**
 * "진행 중인 핸드" 는 HoldemTable 애그리거트가 모르는 개념이라(핸드는 별도 애그리거트) 이 예외들은
 * application 계층에 둔다. 참가 대기열([JoinRequestNotFoundException])도 같은 이유로 여기 있다 —
 * 대기열은 이제 HoldemTable 이 아니라 JoinQueue 포트가 아는 개념이다. NotConnectedException 도
 * 마찬가지다 — STOMP 연결 여부는 도메인이 알 수 없는, 대기열 입장 시점의 응용 규칙이다.
 */
class TableNotFoundException(logMessage: String) : BusinessException(HoldemErrorCode.TABLE_NOT_FOUND, logMessage)
class HandInProgressException(logMessage: String) : BusinessException(HoldemErrorCode.HAND_IN_PROGRESS, logMessage)
class JoinRequestNotFoundException(logMessage: String) : BusinessException(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, logMessage)
class NotConnectedException(logMessage: String) : BusinessException(HoldemErrorCode.NOT_CONNECTED, logMessage)
