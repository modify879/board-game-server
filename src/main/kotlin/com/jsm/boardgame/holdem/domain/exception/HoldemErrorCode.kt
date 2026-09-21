package com.jsm.boardgame.holdem.domain.exception

import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.common.error.ErrorKind

/**
 * 에러 코드는 계층이 아니라 컨텍스트당 하나다 — 예외를 domain 과 application 사이에서
 * 옮기는 리팩터링이 API 변경이 되지 않게 한다.
 */
enum class HoldemErrorCode(override val kind: ErrorKind) : ErrorCode {
    CHIPS_NEGATIVE(ErrorKind.INVALID),
    CHIPS_NOT_UNIT(ErrorKind.INVALID),

    NOT_YOUR_TURN(ErrorKind.CONFLICT),
    BETTING_ROUND_CLOSED(ErrorKind.CONFLICT),
    CANNOT_CHECK(ErrorKind.INVALID),
    RAISE_TOO_SMALL(ErrorKind.INVALID),
    RAISE_NOT_ALLOWED(ErrorKind.CONFLICT),
    INSUFFICIENT_STACK(ErrorKind.INVALID),

    HAND_ALREADY_FINISHED(ErrorKind.CONFLICT),
    NOT_ENOUGH_PLAYERS(ErrorKind.INVALID),
    ;

    override val code: String get() = name
}
