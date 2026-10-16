package com.jsm.boardgame.holdem.domain.model

/** 테스트 준비용: 버튼을 첫 번째 점유 좌석에 둔다(버튼이 null 인 테이블에서 옛 moveButtonToNextOccupiedSeat 과 같다). */
fun HoldemTable.placeButtonAtFirstSeat() {
    HoldemTable::class.java.getDeclaredField("buttonSeatNo")
        .apply { isAccessible = true }
        .set(this, occupiedSeats().first().seatNo)
}
