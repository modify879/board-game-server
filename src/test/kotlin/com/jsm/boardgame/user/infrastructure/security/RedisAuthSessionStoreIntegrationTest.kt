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
import java.time.Duration
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

        val storedValue = redisTemplate.opsForValue().get("auth:session:$userId")

        assertThat(storedValue).isNotNull()
        assertThat(storedValue).doesNotContain(session.refreshToken)
    }

    @Test
    fun `start 직후 키에 TTL 이 설정되어 있다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        val ttl = redisTemplate.getExpire("auth:session:$userId")

        assertThat(ttl).isPositive()
    }

    @Test
    fun `유예 안에서는 직전 리프레시 토큰도 통과한다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val rotated = newSession(previousRefreshToken = first.refreshToken)
        authSessionStore.start(userId, rotated)

        // 새로 발급된 현재 토큰과, 응답 유실로 재시도될 수 있는 직전 토큰 모두 통과해야 한다.
        assertThat(authSessionStore.matchesRefreshToken(userId, rotated.refreshToken)).isTrue()
        assertThat(authSessionStore.matchesRefreshToken(userId, first.refreshToken)).isTrue()
    }

    @Test
    fun `두 세대 전 토큰은 유예 안이어도 거부된다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession(previousRefreshToken = gen0.refreshToken)
        authSessionStore.start(userId, gen1)

        val gen2 = newSession(previousRefreshToken = gen1.refreshToken)
        authSessionStore.start(userId, gen2)

        // 저장소는 직전 한 세대(gen1)만 기억한다 — gen0 은 유예 여부와 무관하게 더 이상 통하지 않는다.
        assertThat(authSessionStore.matchesRefreshToken(userId, gen0.refreshToken)).isFalse()
        assertThat(authSessionStore.matchesRefreshToken(userId, gen1.refreshToken)).isTrue()
        assertThat(authSessionStore.matchesRefreshToken(userId, gen2.refreshToken)).isTrue()
    }

    @Test
    fun `유예가 지나면 직전 토큰이 거부된다`() {
        val shortGraceStore = RedisAuthSessionStore(
            redisTemplate,
            JwtProperties(
                secret = "test-only-secret-value-not-used-by-this-store-12345",
                accessTokenTtl = Duration.ofMinutes(30),
                refreshTokenTtl = Duration.ofDays(14),
                refreshReuseGrace = Duration.ofMillis(200),
            ),
        )
        val userId = newUserId()
        val first = newSession()
        shortGraceStore.start(userId, first)

        val rotated = newSession(previousRefreshToken = first.refreshToken)
        shortGraceStore.start(userId, rotated)

        Thread.sleep(300)

        assertThat(shortGraceStore.matchesRefreshToken(userId, first.refreshToken)).isFalse()
        assertThat(shortGraceStore.matchesRefreshToken(userId, rotated.refreshToken)).isTrue()
    }

    private fun newSession(
        accessTokenId: String = "access-${UUID.randomUUID()}",
        refreshToken: String = "refresh-${UUID.randomUUID()}",
        refreshTokenExpiresAt: Instant = Instant.now().plusSeconds(60 * 60 * 24 * 7),
        previousRefreshToken: String? = null,
    ) = AuthSession(accessTokenId, refreshToken, refreshTokenExpiresAt, previousRefreshToken)

    private fun newUserId(): Long = userIdSequence.getAndIncrement()

    companion object {
        private val userIdSequence = AtomicLong(1)
    }
}
