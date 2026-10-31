package com.jsm.boardgame.user.application.port

import com.jsm.boardgame.user.domain.model.UserRole
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RotationResultTest {

    @Test
    fun `Replayed 의 toString 은 토큰 원문을 담지 않는다`() {
        val replayed = RotationResult.Replayed(
            accessTokenId = "jti-1",
            accessToken = "secret-access-token",
            refreshToken = "secret-refresh-token",
            accessTokenExpiresAt = Instant.parse("2026-01-01T00:00:00Z"),
            refreshTokenExpiresAt = Instant.parse("2026-01-02T00:00:00Z"),
            role = UserRole.USER,
        )

        val text = replayed.toString()

        assertFalse(text.contains("secret-access-token"))
        assertFalse(text.contains("secret-refresh-token"))
        assertTrue(text.contains("jti-1"))
    }
}
