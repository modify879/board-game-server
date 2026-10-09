package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 핸드 정산이 커밋되면 점유 좌석에 지갑 잔액을 합칠 차례라는 신호. 정산 트랜잭션 안에서는 합치지
 * 않는다 — 지갑 행 락 대기·실패가 정산을 롤백시키면 안 되기 때문이다. HandSettler 가 올리기만 하고
 * [com.jsm.boardgame.holdem.infrastructure.timer.SeatTopUpProcessor] 가 커밋 후에 받아 테이블 스레드에서 합친다.
 */
data class SeatTopUpDue(val tableId: TableId)
