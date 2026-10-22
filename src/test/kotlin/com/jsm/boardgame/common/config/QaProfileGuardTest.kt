package com.jsm.boardgame.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QaProfileGuardTest {

    private val local = "jdbc:postgresql://localhost:5433/board"

    private fun v(profiles: List<String> = listOf("qa"), url: String = local, env: String? = null) =
        QaProfileGuard.violations(profiles, url, env)

    @Test
    fun `로컬 DB 이고 운영 표시가 없으면 통과한다`() {
        assertEquals(emptyList(), v())
        assertEquals(emptyList(), v(url = "jdbc:postgresql://127.0.0.1:5432/x?a=b", env = "local"))
        assertEquals(emptyList(), v(url = "jdbc:postgresql://[::1]:5432/x"))
        assertEquals(emptyList(), v(env = " LOCAL "))
    }

    @Test
    fun `운영 프로필이 함께 켜지면 거부한다`() {
        listOf("prod", "Production", "live", "prd").forEach { assertTrue(v(listOf("qa", it)).isNotEmpty(), it) }
    }

    @Test
    fun `DB 호스트가 로컬이 아니거나 읽을 수 없으면 거부한다`() {
        listOf(
            "jdbc:postgresql://db.example.com:5432/x",
            "jdbc:postgresql://10.0.0.5/x",
            "jdbc:postgresql://localhost.evil.com/x",
            "jdbc:postgresql://localhost:5432,db.example.com:5432/x",
            "garbage",
        ).forEach { assertTrue(v(url = it).isNotEmpty(), it) }
    }

    @Test
    fun `APP_ENV 가 설정돼 있고 local 이 아니면 거부한다`() {
        listOf("prod", "staging", "").forEach { assertTrue(v(env = it).isNotEmpty(), "'$it'") }
    }
}
