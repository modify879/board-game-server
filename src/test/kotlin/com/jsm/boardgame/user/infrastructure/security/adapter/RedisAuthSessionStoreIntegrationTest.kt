package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.IntegrationTest
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
import com.jsm.boardgame.user.infrastructure.security.config.JwtProperties
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.RotationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 테스트가 시간을 직접 앞당길 수 있는 [Clock]. `Thread.sleep` 으로 실제 시간이 흐르길
 * 기다리는 대신, [advanceBy] 로 기준 시각을 원하는 만큼만 밀어 결정론적으로 검증한다.
 *
 * [instant] 를 `@Volatile` 로 둔다 — 동시성 테스트(같은 토큰으로 두 스레드가 동시에 rotate)가
 * 이 시계를 두 스레드에서 동시에 읽으므로, 가시성 없이 두면 한쪽 스레드가 갱신 전 값을
 * 볼 수 있다(이 테스트에서는 값을 advanceBy 하지 않지만, 다른 필드 접근과 마찬가지로 안전하게 둔다).
 */
// ClockTestConfig(아래, public @Bean 메서드의 반환 타입)에서 써야 하므로 private 로 좁히지 않는다 —
// Kotlin 은 public 선언이 그보다 좁은 가시성의 타입을 노출하는 것을 컴파일 에러로 막는다.
class RedisSessionMutableClock(
    startingAt: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    @Volatile
    private var instant: Instant = startingAt

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = RedisSessionMutableClock(instant, zone)

    override fun instant(): Instant = instant

    fun advanceBy(duration: Duration) {
        instant = instant.plus(duration)
    }
}

/**
 * Redis 기반 [AuthSessionStore] 구현(`RedisAuthSessionStore`) 통합 테스트.
 *
 * 테스트 간 격리는 각 테스트가 고유한 userId/jti 를 쓰는 방식으로 확보한다 —
 * 스프링 테스트 컨텍스트(와 그 안의 Redis 컨테이너)가 테스트 메서드 간에 재사용되기 때문이다.
 *
 * 직전 토큰 유예 칸은 [start] 로는 만들 수 없다 — 로그인은 항상 그 칸을 비운다. 여러 세대를
 * 재현해야 하는 테스트는 [AuthSessionStore.rotate] 를 연달아 호출해 상태를 만든다.
 *
 * [ClockTestConfig] 가 애플리케이션의 `Clock.systemUTC()` 빈을 `@Primary` [RedisSessionMutableClock] 으로
 * 덮어써서, `authSessionStore`(스프링이 주입한 실 구현체) 가 보는 시각을 테스트가 직접
 * 조작할 수 있게 한다 — 유예 만료를 기다리려고 실제로 잠들 필요가 없다.
 * 단, 동시성 테스트는 실제 스레드 경합을 검증하는 것이므로 시계를 앞당기지 않는다.
 */
@IntegrationTest
class RedisAuthSessionStoreIntegrationTest {

    @Autowired
    private lateinit var authSessionStore: AuthSessionStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var clock: RedisSessionMutableClock

    @TestConfiguration
    class ClockTestConfig {
        // 빈 이름을 "clock"으로 그대로 두면 ClockConfig 의 운영용 clock 빈과 이름이 겹쳐
        // BeanDefinitionOverrideException 이 난다(스프링 부트는 기본적으로 빈 재정의를 막는다).
        // 이름을 달리하고 @Primary 로 타입 기준 주입에서 이 빈이 이기게 한다.
        @Bean
        @Primary
        fun testClock(): RedisSessionMutableClock = RedisSessionMutableClock(Instant.now())
    }

    @Test
    fun `start 후 rotate 는 저장된 토큰이면 Rotated, 다른 토큰이면 Mismatch 다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        // Mismatch 는 상태를 바꾸지 않으므로 먼저 확인해도 아래 성공 케이스에 영향이 없다.
        val mismatchResult = authSessionStore.rotateWith(userId, "다른-리프레시-토큰", newSession())
        assertThat(mismatchResult).isEqualTo(RotationResult.Mismatch)

        val matchResult = authSessionStore.rotateWith(userId, session.refreshToken, newSession())
        assertThat(matchResult).isEqualTo(RotationResult.Rotated(session.accessTokenId))
    }

    @Test
    fun `start 를 두 번 하면 나중 세션만 유효하다`() {
        val userId = newUserId()
        val firstSession = newSession()
        val secondSession = newSession()

        authSessionStore.start(userId, firstSession)
        authSessionStore.start(userId, secondSession)

        // Mismatch 는 상태를 바꾸지 않으므로 먼저 확인해도 아래 성공 케이스에 영향이 없다.
        val firstResult = authSessionStore.rotateWith(userId, firstSession.refreshToken, newSession())
        assertThat(firstResult).isEqualTo(RotationResult.Mismatch)

        val secondResult = authSessionStore.rotateWith(userId, secondSession.refreshToken, newSession())
        assertThat(secondResult).isEqualTo(RotationResult.Rotated(secondSession.accessTokenId))
    }

    @Test
    fun `clear 후에는 rotate 가 Mismatch, currentAccessTokenId 가 null 이다`() {
        val userId = newUserId()
        val session = newSession()
        authSessionStore.start(userId, session)

        authSessionStore.clear(userId)

        val result = authSessionStore.rotateWith(userId, session.refreshToken, newSession())
        assertThat(result).isEqualTo(RotationResult.Mismatch)
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

    // "세션이 없으면 Mismatch 다" 부분은 `세션이 없는 사용자는 rotate 가 Mismatch 다` 가
    // 정확히 같은 것을 rotate 로 검증하므로 여기서는 중복하지 않는다.
    @Test
    fun `세션이 없는 사용자는 currentAccessTokenId 가 null 이다`() {
        val userId = newUserId()

        assertThat(authSessionStore.currentAccessTokenId(userId)).isNull()
    }

    @Test
    fun `리프레시 토큰 원문은 Redis 에 그대로 저장되지 않는다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        val storedValue = redisTemplate.opsForValue().get("auth:session:${userId.value}")

        assertThat(storedValue).isNotNull()
        assertThat(storedValue).doesNotContain(session.refreshToken)
    }

    @Test
    fun `start 직후 키에 TTL 이 설정되어 있다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        val ttl = redisTemplate.getExpire("auth:session:${userId.value}")

        assertThat(ttl).isPositive()
    }

    @Test
    fun `rotate 직후 새로 발급된 현재 토큰은 곧바로 다시 rotate 된다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val rotated = newSession()
        authSessionStore.rotateWith(userId, first.refreshToken, rotated)

        val next = newSession()
        val result = authSessionStore.rotateWith(userId, rotated.refreshToken, next)

        assertThat(result).isEqualTo(RotationResult.Rotated(rotated.accessTokenId))
    }

    @Test
    fun `유예가 지나면 직전 토큰이 거부된다`() {
        val shortGraceClock = RedisSessionMutableClock(Instant.now())
        val shortGraceStore = RedisAuthSessionStore(
            redisTemplate,
            JwtProperties(
                secret = "test-only-secret-value-not-used-by-this-store-12345",
                accessTokenTtl = Duration.ofMinutes(30),
                refreshTokenTtl = Duration.ofDays(14),
                refreshReuseGrace = Duration.ofMillis(200),
            ),
            shortGraceClock,
        )
        val userId = newUserId()
        val first = newSession()
        shortGraceStore.start(userId, first)

        val rotated = newSession()
        shortGraceStore.rotateWith(userId, first.refreshToken, rotated)

        shortGraceClock.advanceBy(Duration.ofMillis(300))

        // Mismatch 는 상태를 바꾸지 않으므로 먼저 확인해도 아래 성공 케이스에 영향이 없다.
        val expiredResult = shortGraceStore.rotateWith(userId, first.refreshToken, newSession())
        assertThat(expiredResult).isEqualTo(RotationResult.Mismatch)

        val currentResult = shortGraceStore.rotateWith(userId, rotated.refreshToken, newSession())
        assertThat(currentResult).isEqualTo(RotationResult.Rotated(rotated.accessTokenId))
    }

    @Test
    fun `현재 토큰으로 rotate 하면 Rotated 이고 교체 전 jti 를 돌려준다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val next = newSession()
        val result = authSessionStore.rotateWith(userId, first.refreshToken, next)

        assertThat(result).isEqualTo(RotationResult.Rotated(first.accessTokenId))
        assertThat(authSessionStore.currentAccessTokenId(userId)).isEqualTo(next.accessTokenId)
    }

    @Test
    fun `유예 안의 직전 토큰을 제시하면 처음 회전의 후속 토큰이 그대로 재생되고 반복해도 같다`() {
        val userId = newUserId()
        val t0 = newSession()
        authSessionStore.start(userId, t0)

        val t1 = newSession()
        authSessionStore.rotateWith(userId, t0.refreshToken, t1)

        val first = authSessionStore.rotateWith(userId, t0.refreshToken, newSession())
        val second = authSessionStore.rotateWith(userId, t0.refreshToken, newSession())

        assertThat(first).isEqualTo(replayOf(t1))
        assertThat(second).isEqualTo(replayOf(t1))
        // 재생은 회전하지 않는다 — 세션은 여전히 t1 이고 t1 은 정상 회전한다.
        assertThat(authSessionStore.currentAccessTokenId(userId)).isEqualTo(t1.accessTokenId)
        val t2 = newSession()
        assertThat(authSessionStore.rotateWith(userId, t1.refreshToken, t2))
            .isEqualTo(RotationResult.Rotated(t1.accessTokenId))
    }

    @Test
    fun `유예가 지난 뒤 직전 토큰을 제시하면 Mismatch 다`() {
        val userId = newUserId()
        val t0 = newSession()
        authSessionStore.start(userId, t0)
        authSessionStore.rotateWith(userId, t0.refreshToken, newSession())

        clock.advanceBy(Duration.ofSeconds(6))

        assertThat(authSessionStore.rotateWith(userId, t0.refreshToken, newSession())).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `한 번 더 회전한 뒤 두 세대 전 토큰을 제시하면 Mismatch 다 - 유예 안이어도`() {
        val userId = newUserId()
        val t0 = newSession()
        authSessionStore.start(userId, t0)
        val t1 = newSession()
        authSessionStore.rotateWith(userId, t0.refreshToken, t1)
        authSessionStore.rotateWith(userId, t1.refreshToken, newSession())

        assertThat(authSessionStore.rotateWith(userId, t0.refreshToken, newSession())).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `재생 레코드는 후속 토큰 평문을 담지 않는다`() {
        val userId = newUserId()
        val t0 = newSession()
        authSessionStore.start(userId, t0)
        val t1 = newSession()
        authSessionStore.rotateWith(userId, t0.refreshToken, t1)

        val own = redisTemplate.opsForValue().get("auth:refresh:replay:${sha256Hex(t0.refreshToken)}")

        assertThat(own).isNotNull()
        assertThat(own).doesNotContain(t1.refreshToken).doesNotContain(accessTokenOf(t1)).doesNotContain(t0.refreshToken)
    }

    @Test
    fun `두 세대 전 토큰으로 rotate 하면 Mismatch 다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession()
        authSessionStore.rotateWith(userId, gen0.refreshToken, gen1)

        val gen2 = newSession()
        authSessionStore.rotateWith(userId, gen1.refreshToken, gen2)

        val attempted = newSession()
        val result = authSessionStore.rotateWith(userId, gen0.refreshToken, attempted)

        assertThat(result).isEqualTo(RotationResult.Mismatch)
        // 실패한 rotate 는 세션을 바꾸지 않는다.
        assertThat(authSessionStore.currentAccessTokenId(userId)).isEqualTo(gen2.accessTokenId)
    }

    @Test
    fun `세션이 없는 사용자는 rotate 가 Mismatch 다`() {
        val userId = newUserId()

        val result = authSessionStore.rotateWith(userId, "아무-토큰", newSession())

        assertThat(result).isEqualTo(RotationResult.Mismatch)
    }

    // 같은 토큰을 N 스레드가 동시에 제시하면 Lua 직렬화로 하나만 현재 토큰과 일치해 Rotated 가 되고,
    // 나머지는 그 회전이 남긴 재생 레코드를 받는다 — 전부 이긴 쪽의 후속 토큰과 같다.
    @Test
    fun `같은 토큰으로 여러 스레드가 동시에 rotate 하면 하나만 Rotated 이고 나머지는 같은 토큰을 재생받는다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val n = 8
        val nextSessions = List(n) { newSession() }
        val readyLatch = CountDownLatch(n)
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(n)

        val futures = nextSessions.map { next ->
            executor.submit(
                Callable {
                    readyLatch.countDown()
                    startLatch.await()
                    authSessionStore.rotateWith(userId, first.refreshToken, next)
                },
            )
        }

        readyLatch.await()
        startLatch.countDown()
        val outcomes = futures.map { it.get(10, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertThat(outcomes.count { it is RotationResult.Rotated }).isEqualTo(1)
        val winner = nextSessions[outcomes.indexOfFirst { it is RotationResult.Rotated }]
        val replays = outcomes.filterIsInstance<RotationResult.Replayed>()
        assertThat(replays).hasSize(n - 1)
        assertThat(replays).allSatisfy { assertThat(it).isEqualTo(replayOf(winner)) }
        assertThat(authSessionStore.currentAccessTokenId(userId)).isEqualTo(winner.accessTokenId)
    }

    @Test
    fun `rotate 후에도 키에 TTL 이 남아 있다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val next = newSession()
        authSessionStore.rotateWith(userId, first.refreshToken, next)

        val ttl = redisTemplate.getExpire("auth:session:${userId.value}")

        assertThat(ttl).isPositive()
    }

    @Test
    fun `발급된 적 없는 토큰은 userIdForRefreshToken 이 null 이다`() {
        assertThat(authSessionStore.userIdForRefreshToken("아무도-발급한-적-없는-토큰")).isNull()
    }

    @Test
    fun `start 한 리프레시 토큰은 userIdForRefreshToken 으로 사용자를 찾을 수 있다`() {
        val userId = newUserId()
        val session = newSession()

        authSessionStore.start(userId, session)

        assertThat(authSessionStore.userIdForRefreshToken(session.refreshToken)).isEqualTo(userId)
    }

    @Test
    fun `회전 뒤에도 옛 토큰의 인덱스가 남아 재사용 탐지가 동작한다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val rotated = newSession()
        authSessionStore.rotateWith(userId, first.refreshToken, rotated)

        assertThat(authSessionStore.userIdForRefreshToken(first.refreshToken)).isEqualTo(userId)

        // first 는 rotated 의 유예 대상 직전 토큰이지만, 유예(5초)를 넘겨 제시하면 거부된다.
        clock.advanceBy(Duration.ofSeconds(6))

        val reused = newSession()
        val result = authSessionStore.rotateWith(userId, first.refreshToken, reused)

        assertThat(result).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `rotate 로 발급된 새 토큰도 인덱스에서 찾을 수 있다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val rotated = newSession()
        authSessionStore.rotateWith(userId, first.refreshToken, rotated)

        assertThat(authSessionStore.userIdForRefreshToken(rotated.refreshToken)).isEqualTo(userId)
    }

    private fun accessTokenOf(session: AuthSession) = "jwt-${session.accessTokenId}"

    private fun AuthSessionStore.rotateWith(userId: UserId, presented: String, next: AuthSession) =
        rotate(userId, presented, next, accessTokenOf(next), ACCESS_EXPIRES_AT, UserRole.USER)

    private fun replayOf(session: AuthSession) = RotationResult.Replayed(
        accessTokenId = session.accessTokenId,
        accessToken = accessTokenOf(session),
        refreshToken = session.refreshToken,
        accessTokenExpiresAt = ACCESS_EXPIRES_AT,
        refreshTokenExpiresAt = session.refreshTokenExpiresAt,
        role = UserRole.USER,
    )

    private fun sha256Hex(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newSession(
        accessTokenId: String = "access-${UUID.randomUUID()}",
        refreshToken: String = "refresh-${UUID.randomUUID()}",
        refreshTokenExpiresAt: Instant = Instant.now().plusSeconds(60 * 60 * 24 * 7),
    ) = AuthSession(accessTokenId, refreshToken, refreshTokenExpiresAt)

    private fun newUserId(): UserId = UserId(userIdSequence.getAndIncrement())

    companion object {
        // 다른 통합 테스트가 같은 Redis 에 실제 사용자(IDENTITY 1, 2, 3…)의 세션을 남기므로 겹치지 않는 대역에서 시작한다.
        private val ACCESS_EXPIRES_AT: Instant = Instant.parse("2099-01-01T00:00:00Z")
        private val userIdSequence = AtomicLong(1_000_000_000_000L)
    }
}
