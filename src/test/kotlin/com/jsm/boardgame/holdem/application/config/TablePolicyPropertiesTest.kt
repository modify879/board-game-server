package com.jsm.boardgame.holdem.application.config

import com.jsm.boardgame.holdem.domain.exception.HoldemErrorCode
import com.jsm.boardgame.holdem.domain.exception.InvalidTablePolicyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TablePolicyPropertiesTest {

    @Test
    fun `설정이 불변식을 어기면 바인딩 단계에서 실패한다 - 기동 실패`() {
        val blinds = assertFailsWith<InvalidTablePolicyException> { TablePolicyProperties(200, 100, 20, 100) }
        val buyIns = assertFailsWith<InvalidTablePolicyException> { TablePolicyProperties(100, 200, 100, 20) }
        assertEquals(HoldemErrorCode.TABLE_POLICY_INVALID, blinds.errorCode)
        assertEquals(HoldemErrorCode.TABLE_POLICY_INVALID, buyIns.errorCode)
    }

    @Test
    fun `정상 설정은 정책으로 바뀐다`() {
        assertEquals(200L, TablePolicyProperties(100, 200, 20, 100).toPolicy().bigBlind.amount)
    }
}
