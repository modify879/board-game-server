package com.jsm.boardgame.user.domain.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `changeNickname 하면 닉네임이 바뀐다`() {
        val user = newUser()

        user.changeNickname(Nickname.of("새닉네임"))

        assertEquals(Nickname.of("새닉네임"), user.nickname)
    }

    @Test
    fun `changePassword 하면 비밀번호 해시가 바뀐다`() {
        val user = newUser()

        user.changePassword(PasswordHash("new-hash"))

        assertEquals(PasswordHash("new-hash"), user.passwordHash)
    }

    @Test
    fun `changeProfileImage 로 키를 정하고 null 로 기본 이미지로 돌린다`() {
        val user = newUser()

        user.changeProfileImage(ProfileImageKey.of("1/a.png"))
        assertEquals(ProfileImageKey.of("1/a.png"), user.profileImageKey)

        user.changeProfileImage(null)
        assertEquals(null, user.profileImageKey)
    }

    private fun savedUser(imageKey: String? = null): User = User.reconstitute(
        id = UserId(42),
        username = Username.of("user_42"),
        passwordHash = PasswordHash("hashed"),
        nickname = Nickname.of("길동이"),
        profileImageKey = imageKey?.let { ProfileImageKey.of(it) },
        role = UserRole.USER,
    )

    @Test
    fun `withdraw 하면 탈퇴 시각이 기록되고 닉네임은 묘비가 되며 이미지 키는 지워지고 이전 키를 돌려준다`() {
        val user = savedUser(imageKey = "42/a.png")
        val at = Instant.now()

        val previous = user.withdraw(at)

        assertEquals(at, user.withdrawnAt)
        assertEquals("withdrawn-user-42", user.nickname.value)
        assertEquals(null, user.profileImageKey)
        assertEquals(ProfileImageKey.of("42/a.png"), previous)
    }

    @Test
    fun `묘비 닉네임은 유효한 닉네임 최대 길이보다 길어 겹칠 수 없다`() {
        val user = savedUser()
        user.withdraw(Instant.now())

        assertTrue(user.nickname.value.codePointCount(0, user.nickname.value.length) > 12)
    }

    @Test
    fun `저장되지 않은 사용자는 탈퇴할 수 없다`() {
        assertFailsWith<IllegalStateException> { newUser().withdraw(Instant.now()) }
    }
}
