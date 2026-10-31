package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.Jwt
import java.time.Instant

class JwtBlacklistValidatorTest {

    private val validator = JwtBlacklistValidator(BlacklistFakeAuthSessionStore(setOf("revoked")))

    private fun jwt(jti: String?): Jwt = Jwt.withTokenValue("t")
        .header("alg", "none")
        .subject("1")
        .apply { if (jti != null) jti(jti) }
        .build()

    @Test
    fun `블랙리스트에 없는 jti 는 통과한다`() {
        assertThat(validator.validate(jwt("ok")).hasErrors()).isFalse()
    }

    @Test
    fun `블랙리스트에 오른 jti 는 거부한다`() {
        assertThat(validator.validate(jwt("revoked")).hasErrors()).isTrue()
    }

    @Test
    fun `jti 가 없는 토큰은 거부한다`() {
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue()
    }
}

private class BlacklistFakeAuthSessionStore(private val blacklisted: Set<String>) : AuthSessionStore {
    override fun isAccessTokenBlacklisted(accessTokenId: String) = accessTokenId in blacklisted
    override fun start(userId: UserId, session: AuthSession) = error("unused")
    override fun currentAccessTokenId(userId: UserId): String? = error("unused")
    override fun clear(userId: UserId) = error("unused")
    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) = error("unused")
    override fun rotate(userId: UserId, presentedRefreshToken: String, next: AuthSession, nextAccessToken: String, nextAccessTokenExpiresAt: Instant, nextRole: UserRole): RotationResult = error("unused")
    override fun userIdForRefreshToken(refreshToken: String): UserId? = error("unused")
}
