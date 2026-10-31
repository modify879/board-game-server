package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.model.UserRole
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RefreshReplayCipherTest {
    private val successor = RotationResult.Replayed(
        accessTokenId = "jti-1",
        accessToken = "eyJhbGciOi.payload.sig-_",
        refreshToken = "rt-abc_DEF-123",
        accessTokenExpiresAt = Instant.parse("2030-01-01T00:00:00.123456789Z"),
        refreshTokenExpiresAt = Instant.parse("2030-01-15T00:00:00Z"),
        role = UserRole.ADMIN,
    )

    @Test
    fun `같은 토큰으로 암호화한 페이로드는 그대로 복호화된다`() {
        val payload = RefreshReplayCipher.encrypt("presented-token", successor)

        assertEquals(successor, RefreshReplayCipher.decrypt("presented-token", payload))
    }

    @Test
    fun `다른 토큰으로는 복호화되지 않는다`() {
        val payload = RefreshReplayCipher.encrypt("presented-token", successor)

        assertNull(RefreshReplayCipher.decrypt("other-token", payload))
    }

    @Test
    fun `같은 입력도 IV 가 달라 암호문이 매번 다르다`() {
        assertNotEquals(
            RefreshReplayCipher.encrypt("presented-token", successor),
            RefreshReplayCipher.encrypt("presented-token", successor),
        )
    }
}
