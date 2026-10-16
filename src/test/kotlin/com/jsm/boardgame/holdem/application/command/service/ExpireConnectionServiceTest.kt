package com.jsm.boardgame.holdem.application.command.service

import com.jsm.boardgame.holdem.application.port.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.application.port.FakeHandStore
import com.jsm.boardgame.holdem.application.command.usecase.ExpireConnectionCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpResult
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class ExpireConnectionFakeStandUpUseCase : StandUpUseCase {
    val calls = mutableListOf<StandUpCommand>()
    override fun standUp(command: StandUpCommand): StandUpResult {
        calls += command
        return StandUpResult.STOOD_UP
    }

}

class ExpireConnectionServiceTest {

    private val tables = FakeHoldemTableRepository()
    private val handStore = FakeHandStore()
    private val standUpUseCase = ExpireConnectionFakeStandUpUseCase()
    private val service = ExpireConnectionService(tables, handStore, standUpUseCase)

    @Test
    fun `미착석 사용자는 조용히 끝나고 null 을 돌려준다`() {
        val result = service.expire(ExpireConnectionCommand(userId = 999))

        assertNull(result)
        assertEquals(0, standUpUseCase.calls.size)
    }

    @Test
    fun `핸드가 없으면 즉시 기립시키고 null 을 돌려준다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(UserId(1L), Chips.of(8_000))
        tables.save(table)

        val result = service.expire(ExpireConnectionCommand(userId = 1))

        assertNull(result)
        assertEquals(1, standUpUseCase.calls.size)
        assertEquals(1L, standUpUseCase.calls[0].userId)
    }

    @Test
    fun `핸드가 진행 중이면 즉시 기립시키지 않고 테이블 id 를 돌려준다`() {
        val table = tables.save(HoldemTable.create("테스트 테이블"))
        table.sitDown(UserId(1L), Chips.of(8_000))
        val saved = tables.save(table)
        handStore.save(
            saved.id!!,
            Hand.start(
                mapOf(1 to Chips.of(8_000), 2 to Chips.of(8_000)),
                buttonSeatNo = 1,
                smallBlindSeatNo = 1,
                bigBlindSeatNo = 2,
                HoldemTable.SMALL_BLIND,
                HoldemTable.BIG_BLIND,
                Shuffler { it },
            ),
        )

        val result = service.expire(ExpireConnectionCommand(userId = 1))

        assertEquals(saved.id, result)
        assertEquals(0, standUpUseCase.calls.size)
    }

    @Test
    fun `30초 무응답 폴드로 이미 기립한 사용자는 연결 만료 처리도 조용히 끝난다`() {
        // ExpireTurnService 가 30초 무응답 폴드로 이미 기립시켰다고 가정 — 이 사용자는 이제 어느
        // 테이블에도 앉아 있지 않다.
        val result = service.expire(ExpireConnectionCommand(userId = 1))

        assertNull(result)
        assertEquals(0, standUpUseCase.calls.size)
    }
}
