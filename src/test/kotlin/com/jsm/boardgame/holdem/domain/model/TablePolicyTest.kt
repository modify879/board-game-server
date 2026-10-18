package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.InvalidChipsException
import com.jsm.boardgame.holdem.domain.exception.InvalidTablePolicyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TablePolicyTest {

    @Test
    fun `정상 정책은 BB 배수로 바이인 범위를 계산한다`() {
        val policy = TablePolicy.of(500, 1_000, 10, 50)

        assertEquals(Chips.of(10_000), policy.minBuyIn)
        assertEquals(Chips.of(50_000), policy.maxStack)
        assertEquals(Chips.of(10_000)..Chips.of(50_000), policy.buyInRange(Chips.ZERO))
    }

    @Test
    fun `블라인드가 양수가 아니거나 small 이 big 이상이면 TABLE_POLICY_INVALID`() {
        for ((small, big) in listOf(0L to 200L, 200L to 200L, 300L to 200L)) {
            val e = assertFailsWith<InvalidTablePolicyException> { TablePolicy.of(small, big, 20, 100) }
            assertEquals(HoldemErrorCode.TABLE_POLICY_INVALID, e.errorCode)
        }
    }

    @Test
    fun `바이인 BB 범위가 양수가 아니거나 min 이 max 보다 크면 TABLE_POLICY_INVALID`() {
        for ((min, max) in listOf(0 to 100, 20 to 10, -1 to 5)) {
            val e = assertFailsWith<InvalidTablePolicyException> { TablePolicy.of(100, 200, min, max) }
            assertEquals(HoldemErrorCode.TABLE_POLICY_INVALID, e.errorCode)
        }
    }

    @Test
    fun `블라인드가 칩 단위의 배수가 아니면 CHIPS_NOT_UNIT`() {
        val e = assertFailsWith<InvalidChipsException> { TablePolicy.of(100, 250, 20, 100) }
        assertEquals(HoldemErrorCode.CHIPS_NOT_UNIT, e.errorCode)
    }

    @Test
    fun `min 과 max 가 같아도 된다`() {
        TablePolicy.of(100, 200, 50, 50)
    }
}
