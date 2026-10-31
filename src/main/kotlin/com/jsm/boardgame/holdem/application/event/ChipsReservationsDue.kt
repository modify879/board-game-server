package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 핸드가 끝나 칩 추가 예약을 적용할 때라는 신호. 핸드가 끝나는 두 곳(HandSettler, CancelHandService)이
 * [JoinRequestsDue] 바로 앞에 올린다 — 발행 순서가 실행기에 올리는 순서라 예약이 대기열 처리·빈 테이블 닫기보다 먼저 적용된다.
 * 지갑 이체가 실패해도 정산 트랜잭션이 rollback-only 가 되지 않게
 * [com.jsm.boardgame.holdem.infrastructure.timer.ChipsReservationsProcessor] 가 커밋 후에 받아 별도 트랜잭션으로 처리한다.
 */
data class ChipsReservationsDue(val tableId: TableId)
