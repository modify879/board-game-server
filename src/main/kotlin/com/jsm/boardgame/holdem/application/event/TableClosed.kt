package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 방치된 테이블이 닫혔다는 신호. 닫힌 트랜잭션 안에서 올리기만 하고, 커밋 후 리스너가 구독자 알림과
 * 테이블별 인메모리 상태 정리를 한다 — 롤백된 닫힘이 알려지거나 정리되면 안 된다.
 *
 * 테이블별 인메모리 상태를 가진 컴포넌트는 이 이벤트를 직접 받아 자기 상태를 치운다(TableId 를 키로 둔 상태는
 * TableClosedListenersTest 가 찾아 리스너를 강제한다). 정리가 상태를 쥔
 * 쪽 옆에 있어야 빠뜨리지 않고, AFTER_COMMIT 리스너는 예외가 나도 Spring 이 로그만 남기고 다음 리스너로
 * 넘어가므로 한 정리의 실패가 다른 정리를 막지 않는다.
 */
data class TableClosed(val tableId: TableId)
