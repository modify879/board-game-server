package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 쇼다운 공개 선택 창(진 좌석의 SHOW/MUCK 선택 대기). 메모리에만 있다 — 재시작하면 사라지고,
 * 그건 전원 MUCK 과 같은 뜻이다(사용자 결정). 진행 중 핸드([HandStore])와 달리 복구 대상이 아니다.
 */
interface ShowdownStore {
    fun find(tableId: TableId): OpenShowdown?
    fun save(tableId: TableId, showdown: OpenShowdown)
    fun remove(tableId: TableId)
}

/** @param seatNoByUserId 선택권자(진 좌석)의 userId → seatNo. 0칩 자동 기립보다 먼저 잡는다 —
 *  올인으로 져서 기립된 사람도 고를 수 있어야 한다. */
data class OpenShowdown(val hand: Hand, val seatNoByUserId: Map<Long, Int>)
