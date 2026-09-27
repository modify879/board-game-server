package com.jsm.boardgame.holdem.infrastructure.timer

import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ProcessJoinRequestsUseCase
import com.jsm.boardgame.holdem.application.event.JoinRequestsDue
import com.jsm.boardgame.holdem.application.port.TableExecutor
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * [JoinRequestsDue] 를 커밋 후에 받아 참가 요청 처리를 건다. NextHandTimer/TurnTimer 와 달리
 * 스케줄러가 필요 없다 — 정산 트랜잭션이 이미 끝난 뒤라 지금 바로 처리해도 된다(직접 호출).
 *
 * `TableExecutor.post` 로 넘긴다(`call` 이 아니다) — 이 리스너는 `AFTER_COMMIT` 이라 지금 막
 * 커밋을 끝낸 스레드 위에서 실행되는데, 그 스레드가 자기 테이블 큐가 비워질 때까지 블로킹하며
 * 기다리면 커미터를 불필요하게 붙잡는다. 그 결과 대기열 머리 착석 처리가 이 이벤트를 발행한
 * 트랜잭션과 같은 스레드/동기가 아니라 테이블 실행기 스레드에서 비동기로 돈다 — 클라이언트가
 * 보기엔 착석이 그 트랜잭션 커밋보다 살짝 늦게 반영될 수 있다는 뜻이다(이전엔 같은 스레드에서
 * 즉시 처리했다).
 */
@Component
class JoinRequestsProcessor(
    private val processJoinRequestsUseCase: ProcessJoinRequestsUseCase,
    private val tableExecutor: TableExecutor,
) {
    @TransactionalEventListener
    fun onJoinRequestsDue(event: JoinRequestsDue) {
        tableExecutor.post(event.tableId) { processJoinRequestsUseCase.process(ProcessJoinRequestsCommand(event.tableId.value)) }
    }
}
