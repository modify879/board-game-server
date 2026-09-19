package com.jsm.boardgame.wallet.domain.model

import com.jsm.boardgame.wallet.domain.exception.InvalidBankAccountException

class BankAccount private constructor(
    val bankName: String,
    val accountNumber: String,
    val accountHolder: String,
) {

    override fun toString(): String {
        val maskedHolder = "*".repeat(accountHolder.codePointCount(0, accountHolder.length))
        val last4 = accountNumber.takeLast(4)
        return "BankAccount(bankName=$bankName, accountNumber=****$last4, accountHolder=$maskedHolder)"
    }

    companion object {
        private const val MIN_NAME_LENGTH = 1
        private const val MAX_NAME_LENGTH = 50
        private const val MIN_ACCOUNT_NUMBER_LENGTH = 8
        private const val MAX_ACCOUNT_NUMBER_LENGTH = 20

        fun of(bankName: String, accountNumber: String, accountHolder: String): BankAccount {
            val normalizedBankName = bankName.trim()
            val normalizedAccountHolder = accountHolder.trim()
            val normalizedAccountNumber = accountNumber.trim().replace("-", "").replace(" ", "")

            if (normalizedBankName.isBlank() || normalizedAccountHolder.isBlank() || normalizedAccountNumber.isBlank()) {
                throw InvalidBankAccountException("계좌 정보에 빈 값이 있음")
            }
            if (!normalizedAccountNumber.all { it.isDigit() }) {
                throw InvalidBankAccountException("계좌번호에 숫자가 아닌 문자가 있음")
            }
            if (normalizedAccountNumber.length !in MIN_ACCOUNT_NUMBER_LENGTH..MAX_ACCOUNT_NUMBER_LENGTH) {
                throw InvalidBankAccountException("계좌번호 길이가 유효하지 않음: length=${normalizedAccountNumber.length}")
            }

            val bankNameLength = normalizedBankName.codePointCount(0, normalizedBankName.length)
            if (bankNameLength !in MIN_NAME_LENGTH..MAX_NAME_LENGTH) {
                throw InvalidBankAccountException("은행명 길이가 유효하지 않음: length=$bankNameLength")
            }

            val accountHolderLength = normalizedAccountHolder.codePointCount(0, normalizedAccountHolder.length)
            if (accountHolderLength !in MIN_NAME_LENGTH..MAX_NAME_LENGTH) {
                throw InvalidBankAccountException("예금주 길이가 유효하지 않음: length=$accountHolderLength")
            }

            return BankAccount(normalizedBankName, normalizedAccountNumber, normalizedAccountHolder)
        }

        /** 영속 복원 전용 — 검증도 정규화도 하지 않는다. */
        fun reconstitute(bankName: String, accountNumber: String, accountHolder: String): BankAccount =
            BankAccount(bankName, accountNumber, accountHolder)
    }
}
