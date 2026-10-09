package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 방치된 테이블이 닫혔다는 신호. 닫힌 트랜잭션 안에서 올리기만 하고, 커밋 후 리스너가 구독자 알림과
 * 테이블별 인메모리 상태 정리를 한다 — 롤백된 닫힘이 알려지거나 정리되면 안 된다.
 */
data class TableClosed(val tableId: TableId)
