package com.jsm.boardgame.user.infrastructure.acl

import com.jsm.boardgame.common.error.CommonErrorCode
import com.jsm.boardgame.user.application.exception.WalletLockContendedException
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.wallet.application.command.usecase.LockWalletForAccountClosureUseCase
import com.jsm.boardgame.wallet.application.exception.ConcurrentWalletUpdateException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class HoldingsFakeClosure(private val result: () -> Boolean) : LockWalletForAccountClosureUseCase {
    override fun lockAndCheckEmpty(userId: Long): Boolean = result()
}

class WalletHoldingsAdapterTest {

    @Test
    fun `비었으면 보유가 없다고 하고 아니면 있다고 한다`() {
        assertFalse(WalletHoldingsAdapter(HoldingsFakeClosure { true }).lockAndHasBalanceOrPendingRequests(UserId(1)))
        assertTrue(WalletHoldingsAdapter(HoldingsFakeClosure { false }).lockAndHasBalanceOrPendingRequests(UserId(1)))
    }

    @Test
    fun `첫 지갑 생성 경합은 user 의 WalletLockContendedException 으로 옮긴다`() {
        val adapter = WalletHoldingsAdapter(HoldingsFakeClosure { throw ConcurrentWalletUpdateException("uk_wallets_user") })

        val e = assertFailsWith<WalletLockContendedException> { adapter.lockAndHasBalanceOrPendingRequests(UserId(1)) }

        assertEquals(CommonErrorCode.TEMPORARILY_UNAVAILABLE, e.errorCode)
    }
}
