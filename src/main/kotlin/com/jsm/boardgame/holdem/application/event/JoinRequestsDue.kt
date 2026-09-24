package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId

/** 핸드 정산 뒤 처리해야 할 참가 요청이 남아 있다는 신호. HandSettler.settle 이 커밋될 트랜잭션
 *  안에서 올리기만 하고, [com.jsm.boardgame.holdem.infrastructure.timer.JoinRequestsProcessor] 가
 *  @TransactionalEventListener(기본 phase = AFTER_COMMIT)로 받아 정산 트랜잭션 밖에서 처리한다 —
 *  참가 요청의 지갑 이체가 실패해도 정산 트랜잭션이 rollback-only 가 되지 않게 하기 위해서다. */
data class JoinRequestsDue(val tableId: TableId)
