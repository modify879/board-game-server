package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.RotationResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
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
class MutableClock(
    startingAt: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {

    @Volatile
    private var instant: Instant = startingAt

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)

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
 * [ClockTestConfig] 가 애플리케이션의 `Clock.systemUTC()` 빈을 `@Primary` [MutableClock] 으로
 * 덮어써서, `authSessionStore`(스프링이 주입한 실 구현체) 가 보는 시각을 테스트가 직접
 * 조작할 수 있게 한다 — 유예 만료·즉시 경합 가드(100ms)를 기다리려고 실제로 잠들 필요가 없다.
 * 단, 동시성 테스트는 실제 스레드 경합을 검증하는 것이므로 시계를 앞당기지 않는다 — 두 스레드가
 * 이 시계에서 정확히 같은 시각을 읽더라도(오히려 경합을 더 확실히 재현한다) 경합 가드가
 * 정확히 하나만 통과시키는 동작 자체는 그대로 검증된다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RedisAuthSessionStoreIntegrationTest {

    @Autowired
    private lateinit var authSessionStore: AuthSessionStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @Autowired
    private lateinit var clock: MutableClock

    @TestConfiguration
    class ClockTestConfig {
        // 빈 이름을 "clock"으로 그대로 두면 ClockConfig 의 운영용 clock 빈과 이름이 겹쳐
        // BeanDefinitionOverrideException 이 난다(스프링 부트는 기본적으로 빈 재정의를 막는다).
        // 이름을 달리하고 @Primary 로 타입 기준 주입에서 이 빈이 이기게 한다.
        @Bean
        @Primary
        fun testClock(): MutableClock = MutableClock(Instant.now())
    }

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

        val rotated = newSession()
        authSessionStore.rotate(userId, first.refreshToken, rotated)

        // 새로 발급된 현재 토큰과, 응답 유실로 재시도될 수 있는 직전 토큰 모두 통과해야 한다.
        assertThat(authSessionStore.matchesRefreshToken(userId, rotated.refreshToken)).isTrue()
        assertThat(authSessionStore.matchesRefreshToken(userId, first.refreshToken)).isTrue()
    }

    @Test
    fun `두 세대 전 토큰은 유예 안이어도 거부된다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession()
        authSessionStore.rotate(userId, gen0.refreshToken, gen1)

        val gen2 = newSession()
        authSessionStore.rotate(userId, gen1.refreshToken, gen2)

        // 저장소는 직전 한 세대(gen1)만 기억한다 — gen0 은 유예 여부와 무관하게 더 이상 통하지 않는다.
        assertThat(authSessionStore.matchesRefreshToken(userId, gen0.refreshToken)).isFalse()
        assertThat(authSessionStore.matchesRefreshToken(userId, gen1.refreshToken)).isTrue()
        assertThat(authSessionStore.matchesRefreshToken(userId, gen2.refreshToken)).isTrue()
    }

    @Test
    fun `유예가 지나면 직전 토큰이 거부된다`() {
        val shortGraceClock = MutableClock(Instant.now())
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
        shortGraceStore.rotate(userId, first.refreshToken, rotated)

        shortGraceClock.advanceBy(Duration.ofMillis(300))

        assertThat(shortGraceStore.matchesRefreshToken(userId, first.refreshToken)).isFalse()
        assertThat(shortGraceStore.matchesRefreshToken(userId, rotated.refreshToken)).isTrue()
    }

    @Test
    fun `현재 토큰으로 rotate 하면 Rotated 이고 교체 전 jti 를 돌려준다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val next = newSession()
        val result = authSessionStore.rotate(userId, first.refreshToken, next)

        assertThat(result).isEqualTo(RotationResult.Rotated(first.accessTokenId))
        assertThat(authSessionStore.matchesRefreshToken(userId, next.refreshToken)).isTrue()
    }

    @Test
    fun `유예 안의 직전 토큰으로 rotate 해도 Rotated 다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession()
        authSessionStore.rotate(userId, gen0.refreshToken, gen1)

        // rotate() 는 유예 창이 열린 직후의 즉시 재사용을 "동시 경합"으로 보고 거부하는
        // 가드(RedisAuthSessionStore.MIN_GRACE_ELAPSED_MILLIS, 100ms)를 둔다. 이 테스트는
        // "응답 유실 후 나중에 재시도"를 재현하는 것이므로, 가드를 여유 있게 넘기고서 호출한다.
        clock.advanceBy(Duration.ofMillis(300))

        // gen0 은 gen1 의 유예 대상 직전 토큰이다.
        val gen2 = newSession()
        val result = authSessionStore.rotate(userId, gen0.refreshToken, gen2)

        assertThat(result).isEqualTo(RotationResult.Rotated(gen1.accessTokenId))
        assertThat(authSessionStore.matchesRefreshToken(userId, gen2.refreshToken)).isTrue()
    }

    @Test
    fun `두 세대 전 토큰으로 rotate 하면 Mismatch 다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession()
        authSessionStore.rotate(userId, gen0.refreshToken, gen1)

        val gen2 = newSession()
        authSessionStore.rotate(userId, gen1.refreshToken, gen2)

        val attempted = newSession()
        val result = authSessionStore.rotate(userId, gen0.refreshToken, attempted)

        assertThat(result).isEqualTo(RotationResult.Mismatch)
        // 실패한 rotate 는 세션을 바꾸지 않는다.
        assertThat(authSessionStore.matchesRefreshToken(userId, gen2.refreshToken)).isTrue()
    }

    @Test
    fun `세션이 없는 사용자는 rotate 가 Mismatch 다`() {
        val userId = newUserId()

        val result = authSessionStore.rotate(userId, "아무-토큰", newSession())

        assertThat(result).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `같은 직전 토큰을 100ms 이상 간격으로 두 번 제시하면 두 번째는 Mismatch 다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession()
        authSessionStore.rotate(userId, gen0.refreshToken, gen1)

        clock.advanceBy(Duration.ofMillis(300))

        // 첫 번째 유예 재시도: gen0 은 아직 직전 칸에 있다 — 통과해야 한다.
        val gen2 = newSession()
        val firstRetry = authSessionStore.rotate(userId, gen0.refreshToken, gen2)
        assertThat(firstRetry).isEqualTo(RotationResult.Rotated(gen1.accessTokenId))

        clock.advanceBy(Duration.ofMillis(300))

        // 같은 gen0 을 다시 제시한다 — 직전 칸에 "제시된 토큰"을 그대로 기록하는 버그가 있었다면
        // 직전 칸이 계속 gen0 으로 재기록되어 여기서도 통과했을 것이다(재사용의 무한 갱신).
        // 고친 뒤에는 직전 칸에 "밀려난 현재 토큰"(gen1)이 들어가므로 gen0 은 어디에도 없어 거부된다.
        val gen3 = newSession()
        val secondRetry = authSessionStore.rotate(userId, gen0.refreshToken, gen3)
        assertThat(secondRetry).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `유예 재시도 후에는 밀려난 현재 토큰이 새 직전 칸에 들어간다`() {
        val userId = newUserId()
        val gen0 = newSession()
        authSessionStore.start(userId, gen0)

        val gen1 = newSession()
        authSessionStore.rotate(userId, gen0.refreshToken, gen1)

        clock.advanceBy(Duration.ofMillis(300))

        // 유예 재시도: 직전 칸의 gen0 을 제시해 통과시킨다 — 이때 밀려나는 것은 "현재" 칸에 있던 gen1 이다.
        val gen2 = newSession()
        val retryResult = authSessionStore.rotate(userId, gen0.refreshToken, gen2)
        assertThat(retryResult).isEqualTo(RotationResult.Rotated(gen1.accessTokenId))

        clock.advanceBy(Duration.ofMillis(300))

        // 밀려난 gen1 이 새 직전 칸에 들어가 있어야 한다 — gen1 으로 rotate 하면 성공해야 한다.
        val gen3 = newSession()
        val rotateWithBumped = authSessionStore.rotate(userId, gen1.refreshToken, gen3)
        assertThat(rotateWithBumped).isEqualTo(RotationResult.Rotated(gen2.accessTokenId))
    }

    // 정확히 하나만 성공하는 이유: 두 스레드 모두 아직 회전되지 않은 first.refreshToken 을
    // 제시하므로, Redis 가 직렬화하는 두 Lua 실행 중 먼저 도는 쪽만 "현재 토큰과 일치"로
    // 통과한다. 나중 쪽은 그 직후 열린 유예 창의 "직전 토큰과 일치" 조건 자체는 만족하지만,
    // 그 창이 열린 지 마이크로초~저수 ms 밖에 지나지 않아 즉시 경합 가드(100ms)에 걸려
    // Mismatch 가 된다 — 응답 유실 후의 정상 재시도(최소 수백 ms 뒤)와는 구별된다.
    @Test
    fun `같은 토큰으로 두 스레드가 동시에 rotate 하면 정확히 하나만 Rotated 다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val nextSessions = List(2) { newSession() }
        val readyLatch = CountDownLatch(2)
        val startLatch = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val futures = nextSessions.map { next ->
            executor.submit(
                Callable {
                    readyLatch.countDown()
                    startLatch.await()
                    authSessionStore.rotate(userId, first.refreshToken, next)
                },
            )
        }

        // 두 스레드 모두 대기 지점에 도달한 뒤 동시에 풀어준다 — 순차 실행이면 경합이 재현되지 않는다.
        readyLatch.await()
        startLatch.countDown()
        val outcomes = futures.map { it.get(5, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertThat(outcomes.count { it is RotationResult.Rotated }).isEqualTo(1)
        assertThat(outcomes.count { it == RotationResult.Mismatch }).isEqualTo(1)

        // 이긴 쪽의 next 세션이 실제로 저장돼 있어야 한다 — 진 쪽이 나중에 덮어쓰지 않았다는 뜻이다.
        val winningNext = nextSessions[outcomes.indexOfFirst { it is RotationResult.Rotated }]
        assertThat(authSessionStore.matchesRefreshToken(userId, winningNext.refreshToken)).isTrue()
    }

    @Test
    fun `rotate 후에도 키에 TTL 이 남아 있다`() {
        val userId = newUserId()
        val first = newSession()
        authSessionStore.start(userId, first)

        val next = newSession()
        authSessionStore.rotate(userId, first.refreshToken, next)

        val ttl = redisTemplate.getExpire("auth:session:$userId")

        assertThat(ttl).isPositive()
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
