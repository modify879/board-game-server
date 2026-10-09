package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpSeatFromWalletUseCase
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsCommand
import com.jsm.boardgame.holdem.application.command.usecase.TopUpTableSeatsUseCase
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import org.springframework.stereotype.Service

/**
 * 착석 중 지갑에 돈이 들어오면(WalletCredited) 핸드가 없을 때 바로 스택에 합치고 공개 뷰를 내보낸다.
 * 핸드가 진행 중이면 아무것도 하지 않는다 — 그 판 스택은 불변이고(table stakes), 정산 직후에 합친다.
 *
 * 호출자(지갑 커밋 후 리스너)는 사용자가 앉은 테이블을 모른다(CancelJoinRequestService 와 같은 이유).
 * 테이블만 찾아 `post` 로 넘기고 기다리지 않는다 — 지갑 스레드는 커밋이 끝났어도 JDBC 커넥션이 아직
 * 바인딩돼 있어, 테이블 큐를 기다리면 커넥션 풀을 붙잡고 idle-in-transaction 타임아웃에 걸릴 수 있다.
 * 실제 합산은 테이블 스레드에서 좌석별 트랜잭션으로 돈다([TopUpTableSeatsService]).
 */
@Service
class TopUpSeatFromWalletService(
    private val tables: HoldemTableRepository,
    private val tableExecutor: TableExecutor,
    private val topUpTableSeats: TopUpTableSeatsUseCase,
) : TopUpSeatFromWalletUseCase {

    override fun topUp(command: TopUpSeatFromWalletCommand) {
        val tableId = tables.findByUserId(UserId(command.userId))?.id ?: return
        tableExecutor.post(tableId) { topUpTableSeats.topUp(TopUpTableSeatsCommand(tableId.value)) }
    }
}
