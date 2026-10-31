package com.jsm.boardgame.holdem.infrastructure.acl

import com.jsm.boardgame.common.error.BusinessException
import com.jsm.boardgame.common.error.ErrorCode
import com.jsm.boardgame.common.error.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private enum class QaRejectFakeErrorCode(override val kind: ErrorKind) : ErrorCode {
    DEPOSIT_REQUEST_ALREADY_PROCESSED(ErrorKind.CONFLICT),
    WITHDRAWAL_REQUEST_ALREADY_PROCESSED(ErrorKind.CONFLICT),
    CONCURRENT_WALLET_UPDATE(ErrorKind.CONFLICT),
    ;

    override val code: String get() = name
}

private class QaRejectFakeException(code: ErrorCode) : BusinessException(code, "fake")

class QaAccountsAdapterTest {

    @Test
    fun `이미 처리된 건은 건너뛰고 나머지는 계속 반려한다`() {
        val rejected = mutableListOf<Long>()

        rejectSkippingProcessed(listOf(3L, 2L, 1L)) {
            if (it == 2L) throw QaRejectFakeException(QaRejectFakeErrorCode.DEPOSIT_REQUEST_ALREADY_PROCESSED)
            if (it == 1L) throw QaRejectFakeException(QaRejectFakeErrorCode.WITHDRAWAL_REQUEST_ALREADY_PROCESSED)
            rejected += it
        }

        assertEquals(listOf(3L), rejected)
    }

    @Test
    fun `이미 처리됨이 아닌 오류는 충돌이어도 그대로 올라간다`() {
        val e = assertFailsWith<QaRejectFakeException> {
            rejectSkippingProcessed(listOf(1L)) { throw QaRejectFakeException(QaRejectFakeErrorCode.CONCURRENT_WALLET_UPDATE) }
        }

        assertEquals(QaRejectFakeErrorCode.CONCURRENT_WALLET_UPDATE, e.errorCode)
    }
}
