package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.domain.model.TableId
import kotlin.test.Test
import kotlin.test.assertEquals

class TableViewSequenceTest {

    @Test
    fun `테이블별로 순번이 독립적으로 증가한다`() {
        val sequence = TableViewSequence()

        assertEquals(1L, sequence.next(TableId(1)))
        assertEquals(2L, sequence.next(TableId(1)))
        assertEquals(1L, sequence.next(TableId(2)))
        assertEquals(3L, sequence.next(TableId(1)))
    }

    @Test
    fun `current 는 증가시키지 않고 아직 next 가 불린 적 없으면 0이다`() {
        val sequence = TableViewSequence()

        assertEquals(0L, sequence.current(TableId(1)))

        sequence.next(TableId(1))
        assertEquals(1L, sequence.current(TableId(1)))
        assertEquals(1L, sequence.current(TableId(1)))
    }
}
