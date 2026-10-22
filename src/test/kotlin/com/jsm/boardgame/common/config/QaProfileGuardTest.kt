package com.jsm.boardgame.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaProfileGuardTest {

    private fun v(rows: Result<List<String>>, profiles: List<String> = listOf("qa")) =
        QaProfileGuard.violations(profiles, rows)

    @Test
    fun `표식이 정확히 한 행이고 qa 이면 통과한다`() {
        assertEquals(emptyList(), v(Result.success(listOf("qa"))))
    }

    @Test
    fun `테이블이 없거나 조회가 실패하면 거부한다`() {
        assertTrue(v(Result.failure(RuntimeException("relation \"app_environment\" does not exist"))).isNotEmpty())
    }

    @Test
    fun `행이 없거나 여러 개면 거부한다`() {
        assertTrue(v(Result.success(emptyList())).isNotEmpty())
        assertTrue(v(Result.success(listOf("qa", "qa"))).isNotEmpty())
    }

    @Test
    fun `값이 qa 가 아니면 거부한다`() {
        assertTrue(v(Result.success(listOf("live"))).isNotEmpty())
    }

    @Test
    fun `운영 프로필이 함께 켜지면 표식이 qa 여도 거부한다`() {
        listOf("prod", "Production", "live", "prd").forEach {
            assertTrue(v(Result.success(listOf("qa")), listOf("qa", it)).isNotEmpty(), it)
        }
    }
}
