package com.jsm.boardgame.wallet.application.config

import kotlin.test.Test
import kotlin.test.assertFailsWith

class WalletPropertiesTest {

    @Test
    fun `최소액이 금액 단위의 배수가 아니거나 양수가 아니면 기동 실패`() {
        assertFailsWith<IllegalArgumentException> { WalletProperties(1_050, 1_000) }
        assertFailsWith<IllegalArgumentException> { WalletProperties(1_000, 950) }
        assertFailsWith<IllegalArgumentException> { WalletProperties(0, 1_000) }
    }

    @Test
    fun `단위의 배수인 양수는 허용된다`() {
        WalletProperties(500, 5_000)
    }
}
