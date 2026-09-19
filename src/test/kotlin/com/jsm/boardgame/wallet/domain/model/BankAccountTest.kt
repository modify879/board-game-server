package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidBankAccountException
import com.jsm.boardgame.wallet.domain.exception.WalletErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BankAccountTest {

    @Test
    fun `은행명이 blank 면 BANK_ACCOUNT_INVALID`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("   ", "123456789012", "홍길동")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `예금주가 blank 면 BANK_ACCOUNT_INVALID`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("국민은행", "123456789012", "   ")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `계좌번호 길이가 8 미만이면 BANK_ACCOUNT_INVALID`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("국민은행", "1234", "홍길동")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `계좌번호 길이가 20 초과면 BANK_ACCOUNT_INVALID`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("국민은행", "1".repeat(21), "홍길동")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `계좌번호에 숫자가 아닌 문자가 남으면 BANK_ACCOUNT_INVALID`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("국민은행", "1234abcd5678", "홍길동")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `하이픈과 공백이 섞여도 정규화되어 숫자만 남는다`() {
        val account = BankAccount.of("국민은행", "123-4567 - 890123", "홍길동")

        assertEquals("1234567890123", account.accountNumber)
    }

    @Test
    fun `toString 에 전체 계좌번호와 예금주 이름이 들어있지 않다`() {
        val account = BankAccount.of("국민은행", "123456789012", "홍길동")

        val text = account.toString()
        assertFalse(text.contains("123456789012"))
        assertFalse(text.contains("홍길동"))
    }

    @Test
    fun `reconstitute 는 검증도 정규화도 하지 않는다`() {
        val account = BankAccount.reconstitute("", "abc-123", "  ")

        assertEquals("", account.bankName)
        assertEquals("abc-123", account.accountNumber)
        assertEquals("  ", account.accountHolder)
    }
}
