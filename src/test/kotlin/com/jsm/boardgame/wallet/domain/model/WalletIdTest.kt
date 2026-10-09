package com.jsm.boardgame.wallet.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WalletIdTest {

    @Test
    fun `문자열로 바꾸면 숫자만 나온다`() {
        assertEquals("1", "${UserId(1)}")
        assertEquals("1", "${WalletId(1)}")
        assertEquals("1", "${LedgerEntryId(1)}")
        assertEquals("1", "${DepositRequestId(1)}")
        assertEquals("1", "${WithdrawalRequestId(1)}")
    }
}
