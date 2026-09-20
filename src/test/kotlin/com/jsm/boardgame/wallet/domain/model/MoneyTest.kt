package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidAmountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `음수는 AMOUNT_NEGATIVE 로 거부된다`() {
        val e = assertFailsWith<InvalidAmountException> { Money.of(-1) }
        assertEquals(WalletErrorCode.AMOUNT_NEGATIVE, e.errorCode)
    }

    @Test
    fun `0은 허용된다`() {
        assertEquals(0L, Money.of(0).amount)
    }

    @Test
    fun `plus 는 금액을 더한다`() {
        assertEquals(Money.of(300), Money.of(100) + Money.of(200))
    }

    @Test
    fun `minus 는 금액을 뺀다`() {
        assertEquals(Money.of(100), Money.of(300) - Money.of(200))
    }

    @Test
    fun `isLessThan 은 크기를 비교한다`() {
        assertTrue(Money.of(100).isLessThan(Money.of(200)))
        assertFalse(Money.of(200).isLessThan(Money.of(100)))
    }

    @Test
    fun `minus 결과가 음수가 되면 AMOUNT_NEGATIVE 로 거부된다`() {
        val e = assertFailsWith<InvalidAmountException> { Money.of(100) - Money.of(200) }
        assertEquals(WalletErrorCode.AMOUNT_NEGATIVE, e.errorCode)
    }

    @Test
    fun `reconstitute 는 음수도 그대로 통과시킨다`() {
        assertEquals(-100L, Money.reconstitute(-100).amount)
    }

    @Test
    fun `plus 오버플로가 조용히 감기지 않는다`() {
        assertFailsWith<ArithmeticException> { Money.of(Long.MAX_VALUE) + Money.of(1) }
    }
}
