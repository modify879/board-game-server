package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.application.port.LastHandResult
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 핸드 상태가 바뀌었으니 좌석별로 브로드캐스트해 달라는 요청. StartScheduledHandService/PlayActionService/
 * HandSettler 가 자기 @Transactional 메서드 안에서 이 이벤트를 publishEvent 로 올리기만 한다.
 * 실제 전송은 HandBroadcaster 가 BEFORE_COMMIT 에서 순번을 따고 afterCommit 동기화로
 * 트랜잭션 커밋 후에 한다 — 커밋 전에 내보내면, 그 뒤 예외로 롤백됐을 때 클라이언트만
 * 서버보다 앞선 상태를 보게 된다.
 */
data class HandBroadcastRequested(
    val tableId: TableId,
    val table: HoldemTable,
    val hand: Hand?,
    /** 방금 끝난 [hand] 의 공개 결과. 끝난 핸드를 싣는 HandSettler 만 채운다 — 그 외에는 null. */
    val lastResult: LastHandResult? = null,
)
