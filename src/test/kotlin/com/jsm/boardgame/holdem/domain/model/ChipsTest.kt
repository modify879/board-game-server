package com.jsm.boardgame.holdem.domain.model

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.InvalidChipsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChipsTest {

    @Test
    fun `음수는 CHIPS_NEGATIVE 로 거부된다`() {
        val e = assertFailsWith<InvalidChipsException> { Chips.of(-1) }
        assertEquals(HoldemErrorCode.CHIPS_NEGATIVE, e.errorCode)
    }

    @Test
    fun `100 단위가 아닌 값은 CHIPS_NOT_UNIT 으로 거부된다`() {
        val e = assertFailsWith<InvalidChipsException> { Chips.of(150) }
        assertEquals(HoldemErrorCode.CHIPS_NOT_UNIT, e.errorCode)
    }

    @Test
    fun `1처럼 작은 값도 CHIPS_NOT_UNIT 으로 거부된다`() {
        val e = assertFailsWith<InvalidChipsException> { Chips.of(1) }
        assertEquals(HoldemErrorCode.CHIPS_NOT_UNIT, e.errorCode)
    }

    @Test
    fun `0은 허용된다`() {
        assertEquals(0L, Chips.of(0).amount)
    }

    @Test
    fun `plus 는 칩을 더한다`() {
        assertEquals(Chips.of(300), Chips.of(100) + Chips.of(200))
    }

    @Test
    fun `minus 는 칩을 뺀다`() {
        assertEquals(Chips.of(100), Chips.of(300) - Chips.of(200))
    }

    @Test
    fun `times 는 칩을 곱한다`() {
        assertEquals(Chips.of(600), Chips.of(200) * 3)
    }

    @Test
    fun `minus 결과가 음수가 되면 CHIPS_NEGATIVE 로 거부된다`() {
        val e = assertFailsWith<InvalidChipsException> { Chips.of(100) - Chips.of(200) }
        assertEquals(HoldemErrorCode.CHIPS_NEGATIVE, e.errorCode)
    }

    @Test
    fun `plus 오버플로가 조용히 감기지 않는다`() {
        assertFailsWith<ArithmeticException> { Chips.of(9223372036854775700) + Chips.of(200) }
    }

    @Test
    fun `times 오버플로가 조용히 감기지 않는다`() {
        assertFailsWith<ArithmeticException> { Chips.of(4611686018427387900) * 3 }
    }

    @Test
    fun `compareTo 로 크기를 비교한다`() {
        assertTrue(Chips.of(100) < Chips.of(200))
        assertTrue(Chips.of(200) > Chips.of(100))
        assertTrue(Chips.of(100) <= Chips.of(100))
        assertTrue(Chips.of(100) >= Chips.of(100))
    }

    @Test
    fun `isZero 는 0 인지 판정한다`() {
        assertTrue(Chips.of(0).isZero())
        assertFalse(Chips.of(100).isZero())
    }

    @Test
    fun `isPositive 는 양수인지 판정한다`() {
        assertTrue(Chips.of(100).isPositive())
        assertFalse(Chips.of(0).isPositive())
    }

    @Test
    fun `min 은 두 칩 중 작은 것을 고른다`() {
        assertEquals(Chips.of(100), Chips.min(Chips.of(100), Chips.of(200)))
        assertEquals(Chips.of(100), Chips.min(Chips.of(200), Chips.of(100)))
        assertEquals(Chips.of(100), Chips.min(Chips.of(100), Chips.of(100)))
    }

    @Test
    fun `reconstitute 는 100 단위가 아닌 값도 그대로 통과시킨다`() {
        assertEquals(150L, Chips.reconstitute(150).amount)
        assertEquals(-100L, Chips.reconstitute(-100).amount)
    }

    @Test
    fun `splitEvenly 는 2500 을 2명이 나누면 1200씩 더하기 나머지 100`() {
        val (each, remainder) = Chips.of(2500).splitEvenly(2)
        assertEquals(Chips.of(1200), each)
        assertEquals(Chips.of(100), remainder)
    }

    @Test
    fun `splitEvenly 는 나눠떨어지는 경우 나머지가 0이다`() {
        val (each, remainder) = Chips.of(2000).splitEvenly(2)
        assertEquals(Chips.of(1000), each)
        assertEquals(Chips.of(0), remainder)
    }

    @Test
    fun `splitEvenly 는 3명 분할을 지원한다`() {
        val (each, remainder) = Chips.of(1000).splitEvenly(3)
        assertEquals(Chips.of(300), each)
        assertEquals(Chips.of(100), remainder)
    }

    @Test
    fun `splitEvenly 의 나머지는 항상 100 단위 배수다`() {
        val (_, remainder) = Chips.of(2700).splitEvenly(2)
        assertEquals(0L, remainder.amount % CHIP_UNIT)
    }
}
