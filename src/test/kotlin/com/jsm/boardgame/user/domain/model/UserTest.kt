package com.jsm.boardgame.user.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserTest {

    private fun newUser(): User = User.register(
        username = Username.of("user_01"),
        passwordHash = PasswordHash("hashed"),
        nickname = Nickname.of("길동이"),
    )

    @Test
    fun `신규 가입 사용자는 잠기지 않은 상태다`() {
        val user = newUser()
        assertFalse(user.isLocked)
    }

    @Test
    fun `lock 하면 잠긴 상태가 되고 lockedAt 이 기록된다`() {
        val user = newUser()
        val at = Instant.now()

        user.lock(at)

        assertTrue(user.isLocked)
        assertEquals(at, user.lockedAt)
    }

    @Test
    fun `이미 잠긴 사용자를 다시 lock 해도 최초 시각을 유지한다`() {
        val user = newUser()
        val first = Instant.now()
        user.lock(first)

        user.lock(first.plusSeconds(60))

        assertEquals(first, user.lockedAt)
    }

    @Test
    fun `unlock 하면 잠금이 풀린다`() {
        val user = newUser()
        user.lock(Instant.now())

        user.unlock()

        assertFalse(user.isLocked)
    }
}
