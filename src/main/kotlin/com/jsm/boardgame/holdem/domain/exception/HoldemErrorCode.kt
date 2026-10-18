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
    HAND_NOT_RUNNING_OUT(ErrorKind.CONFLICT),
    DEPARTURE_NOT_FOLDED(ErrorKind.CONFLICT),
    NOT_ENOUGH_PLAYERS(ErrorKind.INVALID),

    TABLE_NAME_INVALID(ErrorKind.INVALID),
    TABLE_POLICY_INVALID(ErrorKind.INVALID),
    SEAT_TAKEN(ErrorKind.CONFLICT),
    ALREADY_SEATED(ErrorKind.CONFLICT),
    // 없는 리소스가 아니라 앉은 적 없는 사용자의 기립 요청이라 404 다.
    NOT_SEATED(ErrorKind.NOT_FOUND),
    JOIN_REQUEST_NOT_FOUND(ErrorKind.NOT_FOUND),
    BUY_IN_OUT_OF_RANGE(ErrorKind.INVALID),
    CHIP_ADD_OUT_OF_RANGE(ErrorKind.INVALID),
    TABLE_NOT_FOUND(ErrorKind.NOT_FOUND),
    TABLE_FULL(ErrorKind.CONFLICT),
    JOIN_QUEUE_FULL(ErrorKind.CONFLICT),
    TABLE_NOT_EMPTY(ErrorKind.CONFLICT),
    HAND_IN_PROGRESS(ErrorKind.CONFLICT),
    HAND_NOT_FOUND(ErrorKind.NOT_FOUND),
    UNKNOWN_ACTION(ErrorKind.INVALID),
    NOT_CONNECTED(ErrorKind.CONFLICT),
    SHOW_NOT_ALLOWED(ErrorKind.CONFLICT),
    // wallet 의 INSUFFICIENT_BALANCE 와 코드 문자열이 같다 — 클라이언트가 하나만 처리하도록 일부러 맞춘다.
    INSUFFICIENT_BALANCE(ErrorKind.CONFLICT),
    ;

    override val code: String get() = name
}
