package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 대기열 처리가 필요할 수 있다는 신호. 세 트리거가 올린다 — 대기열 삽입 커밋(SitDownService),
 * 핸드 정산(HandSettler, 좌석이 비어 있을 수도 채워졌을 수도 있다), 기립 커밋(StandUpService,
 * 좌석 하나가 빈다). 커밋될 트랜잭션 안에서 올리기만 하고,
 * [com.jsm.boardgame.holdem.infrastructure.timer.JoinRequestsProcessor] 가
 * @TransactionalEventListener(기본 phase = AFTER_COMMIT)로 받아 그 트랜잭션 밖에서 처리한다 —
 * 대기열 항목의 지갑 이체가 실패해도 이 트랜잭션이 rollback-only 가 되지 않게 하기 위해서다.
 */
data class JoinRequestsDue(val tableId: TableId)
