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

    @Test
    fun `계좌번호가 blank 면 BANK_ACCOUNT_INVALID`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("국민은행", " - ", "홍길동")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
    }

    @Test
    fun `은행명이 50자를 넘으면 BANK_ACCOUNT_INVALID 이고 50자는 허용된다`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("가".repeat(51), "123456789012", "홍길동")
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
        assertEquals(50, BankAccount.of("가".repeat(50), "123456789012", "홍길동").bankName.length)
    }

    @Test
    fun `예금주가 50자를 넘으면 BANK_ACCOUNT_INVALID 이고 50자는 허용된다`() {
        val e = assertFailsWith<InvalidBankAccountException> {
            BankAccount.of("국민은행", "123456789012", "가".repeat(51))
        }
        assertEquals(WalletErrorCode.BANK_ACCOUNT_INVALID, e.errorCode)
        assertEquals(50, BankAccount.of("국민은행", "123456789012", "가".repeat(50)).accountHolder.length)
    }

    // 길이는 UTF-16 char 가 아니라 코드포인트로 센다 — 보충 문자 25개는 char 50개지만 코드포인트로는 25다.
    @Test
    fun `이름 길이는 코드포인트로 센다`() {
        val emoji = "\uD83D\uDE00"
        assertEquals(50, BankAccount.of("국민은행", "123456789012", emoji.repeat(50)).accountHolder.codePointCount(0, 100))
        assertFailsWith<InvalidBankAccountException> {
            BankAccount.of(emoji.repeat(51), "123456789012", "홍길동")
        }
    }

    @Test
    fun `계좌번호 길이 경계 8자와 20자는 허용된다`() {
        BankAccount.of("국민은행", "1".repeat(8), "홍길동")
        BankAccount.of("국민은행", "1".repeat(20), "홍길동")
    }

    @Test
    fun `앞뒤 공백은 잘라내고 정규화한다`() {
        val account = BankAccount.of("  국민은행 ", "123456789012", " 홍길동  ")

        assertEquals("국민은행", account.bankName)
        assertEquals("홍길동", account.accountHolder)
    }
}
