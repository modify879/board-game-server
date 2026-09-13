package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Redis 기반 [AuthSessionStore] 구현(`RedisAuthSessionStore`) 통합 테스트.
 *
 * 테스트 간 격리는 각 테스트가 고유한 userId/jti 를 쓰는 방식으로 확보한다 —
 * 스프링 테스트 컨텍스트(와 그 안의 Redis 컨테이너)가 테스트 메서드 간에 재사용되기 때문이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RedisAuthSessionStoreIntegrationTest {

    @Autowired
    private lateinit var authSessionStore: AuthSessionStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Test
    fun `start 후 matchesRefreshToken 은 저장된 토큰에 true, 다른 토큰에 false 를 돌려준다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        assertThat(authSessionStore.matchesRefreshToken(userId, session.refreshToken)).isTrue()
        assertThat(authSessionStore.matchesRefreshToken(userId, "다른-리프레시-토큰")).isFalse()
    }

    @Test
    fun `start 를 두 번 하면 나중 세션만 유효하다`() {
        val userId = newUserId()
        val firstSession = newSession()
        val secondSession = newSession()

        authSessionStore.start(userId, firstSession)
        authSessionStore.start(userId, secondSession)

        assertThat(authSessionStore.matchesRefreshToken(userId, firstSession.refreshToken)).isFalse()
        assertThat(authSessionStore.matchesRefreshToken(userId, secondSession.refreshToken)).isTrue()
        assertThat(authSessionStore.currentAccessTokenId(userId)).isEqualTo(secondSession.accessTokenId)
    }

    @Test
    fun `clear 후에는 matchesRefreshToken 이 false, currentAccessTokenId 가 null 이다`() {
        val userId = newUserId()
        val session = newSession()
        authSessionStore.start(userId, session)

        authSessionStore.clear(userId)

        assertThat(authSessionStore.matchesRefreshToken(userId, session.refreshToken)).isFalse()
        assertThat(authSessionStore.currentAccessTokenId(userId)).isNull()
    }

    @Test
    fun `blacklistAccessToken 에 등록한 jti 는 isAccessTokenBlacklisted 가 true, 등록하지 않은 jti 는 false 다`() {
        val blacklistedJti = "jti-${UUID.randomUUID()}"
        val untouchedJti = "jti-${UUID.randomUUID()}"

        authSessionStore.blacklistAccessToken(blacklistedJti, Instant.now().plusSeconds(3600))

        assertThat(authSessionStore.isAccessTokenBlacklisted(blacklistedJti)).isTrue()
        assertThat(authSessionStore.isAccessTokenBlacklisted(untouchedJti)).isFalse()
    }

    @Test
    fun `세션이 없는 사용자는 matchesRefreshToken 이 false, currentAccessTokenId 가 null 이다`() {
        val userId = newUserId()

        assertThat(authSessionStore.matchesRefreshToken(userId, "아무-토큰")).isFalse()
        assertThat(authSessionStore.currentAccessTokenId(userId)).isNull()
    }

    @Test
    fun `리프레시 토큰 원문은 Redis 에 그대로 저장되지 않는다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        val storedHash = redisTemplate.opsForHash<String, String>()
            .get("auth:session:$userId", "refreshTokenHash")

        assertThat(storedHash).isNotNull()
        assertThat(storedHash).isNotEqualTo(session.refreshToken)
    }

    private fun newSession(
        accessTokenId: String = "access-${UUID.randomUUID()}",
        refreshToken: String = "refresh-${UUID.randomUUID()}",
        refreshTokenExpiresAt: Instant = Instant.now().plusSeconds(60 * 60 * 24 * 7),
    ) = AuthSession(accessTokenId, refreshToken, refreshTokenExpiresAt)

    private fun newUserId(): Long = userIdSequence.getAndIncrement()

    companion object {
        private val userIdSequence = AtomicLong(1)
    }
}
