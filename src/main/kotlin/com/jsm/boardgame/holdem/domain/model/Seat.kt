package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.InvalidLeaveReservationException
import com.jsm.boardgame.holdem.domain.exception.InvalidSeatPresenceException

/** 좌석 점유 상태. BettingRound 의 SeatStatus(ACTIVE/FOLDED/ALL_IN)와 다른 개념이라 이름을 가른다. */
enum class SeatPresence {
    SEATED,
    DISCONNECTED,
    ;

    companion object {
        /** 경계를 넘어온 문자열을 접속 상태로 바꾼다. 알 수 없는 값은 SEAT_PRESENCE_INVALID 로 떨어진다. */
        fun of(raw: String): SeatPresence =
            entries.find { it.name == raw } ?: throw InvalidSeatPresenceException("알 수 없는 접속 상태: presence=$raw")
    }
}

/** 퇴장 예약의 종류. 서버 실행(핸드 뒤 기립)은 둘이 같고, 클라이언트가 기립 뒤 행동을 정하는 데만 쓴다. */
enum class LeaveReservation {
    /** 핸드 뒤 일어나 관전자로 남는다. */
    SPECTATE,

    /** 핸드 뒤 일어나 방 목록으로 이동한다. */
    LEAVE,
    ;

    companion object {
        /** 경계를 넘어온 문자열을 예약 종류로 바꾼다. 알 수 없는 값은 LEAVE_RESERVATION_INVALID 로 떨어진다. */
        fun of(raw: String): LeaveReservation =
            entries.find { it.name == raw } ?: throw InvalidLeaveReservationException("알 수 없는 퇴장 예약 종류: after=$raw")
    }
}

class Seat private constructor(
    val seatNo: Int,
    val userId: UserId,
    stack: Chips,
    presence: SeatPresence,
    awaitingBigBlind: Boolean,
    owesImmediatePost: Boolean,
    leaveReservation: LeaveReservation?,
    chipsReservation: Chips?,
) {
    var stack: Chips = stack
        private set

    var presence: SeatPresence = presence
        private set

    /** BB 대기 중이면 true — 이번 핸드가 자기 자리의 BB 가 아니면 참가하지 않는다(최대 한 바퀴).
     * 자연스럽게 BB 가 도달하면 HandStarter 가 [clearAwaitingBigBlind] 로 푼다. */
    var awaitingBigBlind: Boolean = awaitingBigBlind
        private set

    /** 착석 시 "BB 즉시 포스팅"을 선택해 아직 그 대가(추가 BB 한 번)를 치르지 않은 좌석.
     * awaitingBigBlind 는 이 선택 시 착석 즉시 false 가 되므로, "이미 정상 참가 중인 좌석"과
     * "즉시 포스팅을 아직 안 치른 좌석"을 구분할 다른 신호가 없다 — 그래서 별도 축으로 둔다.
     * 이 좌석이 처음 핸드에 참가하는 순간(HandStarter) 단 한 번만 소비되고 이후 계속 false 다. */
    var owesImmediatePost: Boolean = owesImmediatePost
        private set

    /** "이 핸드가 끝나면 일어난다" 예약. 핸드가 끝날 때 좌석이 기립하며 함께 사라진다. */
    var leaveReservation: LeaveReservation? = leaveReservation
        private set

    val leaveReserved: Boolean get() = leaveReservation != null

    /** "이 핸드가 끝나면 지갑에서 이만큼 더 가져온다" 예약(요청한 금액). 핸드가 끝난 뒤 한도·잔액으로 줄여 적용하고 지운다. */
    var chipsReservation: Chips? = chipsReservation
        private set

    /** 스택 증감은 테이블 애그리거트(HoldemTable)를 거친다. */
    internal fun applyStack(newStack: Chips) {
        stack = newStack
    }

    /** 연결 상태 변경도 테이블 애그리거트(HoldemTable)를 거친다. */
    internal fun applyPresence(newPresence: SeatPresence) {
        presence = newPresence
    }

    /** BB 가 자기 자리에 자연스럽게 도달했을 때 테이블 애그리거트가 부른다. */
    internal fun clearAwaitingBigBlind() {
        awaitingBigBlind = false
    }

    /** 즉시 포스팅한 좌석이 처음 핸드에 참가할 때 테이블 애그리거트가 부른다. 한 번만 일어난다. */
    internal fun consumeImmediatePost() {
        owesImmediatePost = false
    }

    internal fun applyLeaveReservation(reservation: LeaveReservation?) {
        leaveReservation = reservation
    }

    internal fun applyChipsReservation(reservation: Chips?) {
        chipsReservation = reservation
    }

    companion object {
        /** seatNo·buyIn 검증은 HoldemTable 이 한다(블라인드 등 테이블 컨텍스트가 필요해서).
         * postBlindImmediately=true 면 곧바로 참가하되(awaitingBigBlind=false) 다음 핸드에 추가 BB 포스팅을 빚진다(owesImmediatePost=true).
         * false(기본값 — 공짜 쪽이 안전한 기본값)면 BB 가 자기 자리에 올 때까지 대기한다. */
        fun of(seatNo: Int, userId: UserId, stack: Chips, postBlindImmediately: Boolean = false): Seat =
            Seat(
                seatNo, userId, stack, SeatPresence.SEATED,
                awaitingBigBlind = !postBlindImmediately,
                owesImmediatePost = postBlindImmediately,
                leaveReservation = null,
                chipsReservation = null,
            )

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(
            seatNo: Int,
            userId: UserId,
            stack: Chips,
            presence: SeatPresence,
            awaitingBigBlind: Boolean = false,
            owesImmediatePost: Boolean = false,
            leaveReservation: LeaveReservation? = null,
            chipsReservation: Chips? = null,
        ): Seat = Seat(seatNo, userId, stack, presence, awaitingBigBlind, owesImmediatePost, leaveReservation, chipsReservation)
    }
}
