package com.jsm.boardgame.holdem.domain.model

/** 좌석 점유 상태. BettingRound 의 SeatStatus(ACTIVE/FOLDED/ALL_IN)와 다른 개념이라 이름을 가른다. */
enum class SeatPresence { SEATED, SITTING_OUT, DISCONNECTED }

class Seat private constructor(
    val seatNo: Int,
    val userId: Long,
    stack: Chips,
    presence: SeatPresence,
) {
    var stack: Chips = stack
        private set

    var presence: SeatPresence = presence
        private set

    /** 스택 증감은 테이블 애그리거트(HoldemTable)를 거친다. */
    internal fun applyStack(newStack: Chips) {
        stack = newStack
    }

    /** 연결 상태 변경도 테이블 애그리거트(HoldemTable)를 거친다. */
    internal fun applyPresence(newPresence: SeatPresence) {
        presence = newPresence
    }

    companion object {
        /** 착석 시점 생성. presence 는 항상 SEATED 로 시작한다. seatNo·buyIn 검증은 HoldemTable 이 한다(블라인드 등 테이블 컨텍스트가 필요해서). */
        fun of(seatNo: Int, userId: Long, stack: Chips): Seat = Seat(seatNo, userId, stack, SeatPresence.SEATED)

        /** 영속 복원 전용 — 검증하지 않는다. */
        fun reconstitute(seatNo: Int, userId: Long, stack: Chips, presence: SeatPresence): Seat =
            Seat(seatNo, userId, stack, presence)
    }
}
