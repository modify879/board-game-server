package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidAdjustmentException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdjustmentTest {

    @Test
    fun `양수는 CREDIT, 음수는 DEBIT 이고 금액은 절댓값이다`() {
        val credit = Adjustment.of(5_000, "이벤트 지급")
        val debit = Adjustment.of(-5_000, "오지급 회수")

        assertEquals(LedgerEntryType.ADMIN_ADJUSTMENT_CREDIT, credit.type)
        assertEquals(LedgerEntryType.ADMIN_ADJUSTMENT_DEBIT, debit.type)
        assertEquals(Money.of(5_000), credit.amount)
        assertEquals(Money.of(5_000), debit.amount)
    }

    @Test
    fun `0 은 ADJUSTMENT_AMOUNT_INVALID 다`() {
        val e = assertFailsWith<InvalidAdjustmentException> { Adjustment.of(0, "사유") }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `100원 단위가 아니면 ADJUSTMENT_AMOUNT_INVALID 다`() {
        val e = assertFailsWith<InvalidAdjustmentException> { Adjustment.of(150, "사유") }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)

        val negative = assertFailsWith<InvalidAdjustmentException> { Adjustment.of(-150, "사유") }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, negative.errorCode)
    }

    @Test
    // 단위 검사가 절댓값 변환보다 먼저여야 한다. 순서가 뒤집히면 Math.absExact 가
    // ArithmeticException 을 던져 400 이 아니라 500 으로 새어 나간다.
    fun `Long MIN_VALUE 는 500 이 아니라 ADJUSTMENT_AMOUNT_INVALID 로 떨어진다`() {
        val e = assertFailsWith<InvalidAdjustmentException> { Adjustment.of(Long.MIN_VALUE, "사유") }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `사유가 공백뿐이면 ADJUSTMENT_REASON_BLANK 다`() {
        val e = assertFailsWith<InvalidAdjustmentException> { Adjustment.of(1_000, "   ") }
        assertEquals(WalletErrorCode.ADJUSTMENT_REASON_BLANK, e.errorCode)
    }

    @Test
    fun `사유의 앞뒤 공백은 정규화된다`() {
        assertEquals("이벤트 지급", Adjustment.of(1_000, "  이벤트 지급  ").reason)
    }

    @Test
    // 금액이 먼저 검사되므로, 둘 다 잘못되면 금액 쪽 코드가 나간다.
    fun `금액과 사유가 둘 다 잘못되면 금액 코드가 우선한다`() {
        val e = assertFailsWith<InvalidAdjustmentException> { Adjustment.of(150, "") }
        assertEquals(WalletErrorCode.ADJUSTMENT_AMOUNT_INVALID, e.errorCode)
    }
}
