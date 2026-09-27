package com.jsm.boardgame.holdem.presentation.ws.payload

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * 대기열 개인 채널(/user/queue/holdem/join-queue) 페이로드. 테이블별 seq(TableViewSequence) 는
 * 붙이지 않는다 — 이 채널은 특정 테이블의 권위 있는 스냅샷이 아니라 대기열 변화에 대한 일시적
 * 안내라서, 클라이언트가 순서를 재구성해야 할 이유가 없다(seq 는 SeatPrivateView/TablePublicView
 * 처럼 재접속 시 스냅샷 정합성이 걸린 채널에만 필요하다).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = JoinQueueView.Position::class, name = "POSITION"),
    JsonSubTypes.Type(value = JoinQueueView.Seated::class, name = "SEATED"),
    JsonSubTypes.Type(value = JoinQueueView.Dropped::class, name = "DROPPED"),
)
sealed interface JoinQueueView {
    data class Position(val tableId: Long, val position: Int) : JoinQueueView
    data class Seated(val tableId: Long, val seatNo: Int) : JoinQueueView
    data class Dropped(val tableId: Long, val errorCode: String) : JoinQueueView
}
