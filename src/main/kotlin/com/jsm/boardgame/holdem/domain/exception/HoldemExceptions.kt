package com.jsm.boardgame.holdem.domain.exception

import com.jsm.boardgame.common.error.BusinessException

/** CHIPS_NEGATIVE 와 CHIPS_NOT_UNIT 둘 다에 쓰이므로 코드를 생성자로 받는다. */
class InvalidChipsException(
    code: HoldemErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

/**
 * 베팅 액션이 규칙에 어긋난 경우. 턴/체크/레이즈/스택은 클라이언트가 문구를 달리 만들어야 하므로
 * 예외 클래스가 아니라 코드로 가른다.
 */
class IllegalBettingActionException(
    code: HoldemErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

class IllegalHandStateException(
    code: HoldemErrorCode,
    logMessage: String,
) : BusinessException(code, logMessage)

class InvalidTableNameException(logMessage: String) : BusinessException(HoldemErrorCode.TABLE_NAME_INVALID, logMessage)
class SeatNoOutOfRangeException(logMessage: String) : BusinessException(HoldemErrorCode.SEAT_NO_OUT_OF_RANGE, logMessage)
class SeatTakenException(logMessage: String) : BusinessException(HoldemErrorCode.SEAT_TAKEN, logMessage)
class AlreadySeatedException(logMessage: String) : BusinessException(HoldemErrorCode.ALREADY_SEATED, logMessage)
class NotSeatedException(logMessage: String) : BusinessException(HoldemErrorCode.NOT_SEATED, logMessage)
class BuyInOutOfRangeException(logMessage: String) : BusinessException(HoldemErrorCode.BUY_IN_OUT_OF_RANGE, logMessage)
class ConcurrentTableUpdateException(logMessage: String) : BusinessException(HoldemErrorCode.CONCURRENT_TABLE_UPDATE, logMessage)
class JoinRequestNotFoundException(logMessage: String) : BusinessException(HoldemErrorCode.JOIN_REQUEST_NOT_FOUND, logMessage)

/** 공개 선택 창이 없거나(정산 직후가 아니거나 만료됨) 그 창의 대상 좌석이 아닐 때(RevealHandService). */
class RevealNotAllowedException(logMessage: String) : BusinessException(HoldemErrorCode.REVEAL_NOT_ALLOWED, logMessage)
