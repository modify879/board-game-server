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
import org.springframework.data.redis.core.StringRedisTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Redis 기반 [AuthSessionStore] 구현(`RedisAuthSessionStore`) 통합 테스트.
 *
 * 테스트 간 격리는 각 테스트가 고유한 userId/jti 를 쓰는 방식으로 확보한다 —
 * 스프링 테스트 컨텍스트(와 그 안의 Redis 컨테이너)가 테스트 메서드 간에 재사용되기 때문이다.
 *
 * 직전 토큰 유예 칸은 [start] 로는 만들 수 없다 — 로그인은 항상 그 칸을 비운다. 여러 세대를
 * 재현해야 하는 테스트는 [AuthSessionStore.rotate] 를 연달아 호출해 상태를 만든다.
 *
 * 유예 판정은 재생 키의 Redis TTL 이 하므로 시계를 조작할 수 없다. 유예 만료를 보는 테스트는 짧은 유예(300ms)의
 * 스토어를 따로 만들고 재생 키가 사라질 때까지 상한 있는 폴링으로 기다린다.
 */
@IntegrationTest
class RedisAuthSessionStoreIntegrationTest {

    @Autowired
    private lateinit var authSessionStore: AuthSessionStore

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val shortGraceStore by lazy {
        RedisAuthSessionStore(
            redisTemplate,
            JwtProperties(
                secret = "test-only-secret-value-not-used-by-this-store-12345",
                accessTokenTtl = Duration.ofMinutes(30),
                refreshTokenTtl = Duration.ofDays(14),
                refreshReuseGrace = SHORT_GRACE,
            ),
            Clock.systemUTC(),
        )
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
        val userId = newUserId()
        val first = newSession()
        shortGraceStore.start(userId, first)

        val rotated = newSession()
        shortGraceStore.rotateWith(userId, first.refreshToken, rotated)

        awaitReplayExpired(first.refreshToken)

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
        shortGraceStore.start(userId, t0)
        shortGraceStore.rotateWith(userId, t0.refreshToken, newSession())

        awaitReplayExpired(t0.refreshToken)

        assertThat(shortGraceStore.rotateWith(userId, t0.refreshToken, newSession())).isEqualTo(RotationResult.Mismatch)
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
    fun `4필드로 저장된 기존 세션도 회전되고 새 값은 3필드다`() {
        val userId = newUserId()
        val old = newSession()
        redisTemplate.opsForValue().set(
            "auth:session:${userId.value}",
            "${old.accessTokenId}:${sha256Hex(old.refreshToken)}::0",
            Duration.ofMinutes(5),
        )

        assertThat(authSessionStore.currentAccessTokenId(userId)).isEqualTo(old.accessTokenId)
        val next = newSession()
        assertThat(authSessionStore.rotateWith(userId, old.refreshToken, next))
            .isEqualTo(RotationResult.Rotated(old.accessTokenId))
        assertThat(redisTemplate.opsForValue().get("auth:session:${userId.value}"))
            .isEqualTo("${next.accessTokenId}:${sha256Hex(next.refreshToken)}:${sha256Hex(old.refreshToken)}")
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
        shortGraceStore.start(userId, first)

        val rotated = newSession()
        shortGraceStore.rotateWith(userId, first.refreshToken, rotated)

        assertThat(shortGraceStore.userIdForRefreshToken(first.refreshToken)).isEqualTo(userId)

        // first 는 rotated 의 유예 대상 직전 토큰이지만, 유예를 넘겨 제시하면 거부된다.
        awaitReplayExpired(first.refreshToken)

        val reused = newSession()
        val result = shortGraceStore.rotateWith(userId, first.refreshToken, reused)

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

    @Test
    fun `start 에 이미 지난 만료 시각을 주면 세션도 인덱스도 쓰지 않는다`() {
        val userId = newUserId()
        val expired = newSession(refreshTokenExpiresAt = Instant.now().minusSeconds(1))

        authSessionStore.start(userId, expired)

        assertThat(redisTemplate.hasKey("auth:session:${userId.value}")).isFalse()
        assertThat(authSessionStore.userIdForRefreshToken(expired.refreshToken)).isNull()
        assertThat(authSessionStore.currentAccessTokenId(userId)).isNull()
    }

    @Test
    fun `이미 만료된 액세스 토큰은 블랙리스트에 쓰지 않는다`() {
        val jti = "jti-${UUID.randomUUID()}"

        authSessionStore.blacklistAccessToken(jti, Instant.now().minusSeconds(1))
        authSessionStore.blacklistAccessToken("$jti-now", Instant.now())

        assertThat(authSessionStore.isAccessTokenBlacklisted(jti)).isFalse()
        assertThat(authSessionStore.isAccessTokenBlacklisted("$jti-now")).isFalse()
    }

    @Test
    fun `유예 안이어도 재생 레코드를 복호화할 수 없으면 Mismatch 다`() {
        val userId = newUserId()
        val t0 = newSession()
        authSessionStore.start(userId, t0)
        authSessionStore.rotateWith(userId, t0.refreshToken, newSession())
        redisTemplate.opsForValue().set("auth:refresh:replay:${sha256Hex(t0.refreshToken)}", "garbage", Duration.ofSeconds(30))

        assertThat(authSessionStore.rotateWith(userId, t0.refreshToken, newSession())).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `다른 토큰의 재생 레코드는 복호화되지 않아 Mismatch 다`() {
        val userId = newUserId()
        val t0 = newSession()
        authSessionStore.start(userId, t0)
        authSessionStore.rotateWith(userId, t0.refreshToken, newSession())
        // 다른 사용자의 유효한 재생 레코드를 가져다 놓아도, 제시한 토큰으로 유도한 키가 달라 풀리지 않는다.
        val otherUser = newUserId()
        val o0 = newSession()
        authSessionStore.start(otherUser, o0)
        authSessionStore.rotateWith(otherUser, o0.refreshToken, newSession())
        val foreign = redisTemplate.opsForValue().get("auth:refresh:replay:${sha256Hex(o0.refreshToken)}")!!
        redisTemplate.opsForValue().set("auth:refresh:replay:${sha256Hex(t0.refreshToken)}", foreign, Duration.ofSeconds(30))

        assertThat(authSessionStore.rotateWith(userId, t0.refreshToken, newSession())).isEqualTo(RotationResult.Mismatch)
    }

    @Test
    fun `저장된 세션 문자열의 필드가 3개 미만이면 currentAccessTokenId 가 null 이다`() {
        val userId = newUserId()
        redisTemplate.opsForValue().set("auth:session:${userId.value}", "only-access-id:hash", Duration.ofMinutes(5))

        assertThat(authSessionStore.currentAccessTokenId(userId)).isNull()
        val shortest = newUserId()
        redisTemplate.opsForValue().set("auth:session:${shortest.value}", "garbage", Duration.ofMinutes(5))
        assertThat(authSessionStore.currentAccessTokenId(shortest)).isNull()
    }

    @Test
    fun `저장된 세션 문자열의 필드가 3개 미만이면 rotate 는 Mismatch 이고 세션을 건드리지 않는다`() {
        val userId = newUserId()
        redisTemplate.opsForValue().set("auth:session:${userId.value}", "only-access-id:hash", Duration.ofMinutes(5))

        assertThat(authSessionStore.rotateWith(userId, "아무-토큰", newSession())).isEqualTo(RotationResult.Mismatch)
        assertThat(redisTemplate.opsForValue().get("auth:session:${userId.value}")).isEqualTo("only-access-id:hash")
    }

    /** 재생 키(PX = 유예)가 사라질 때까지 기다린다. 상한 5초. */
    private fun awaitReplayExpired(presentedRefreshToken: String) {
        val key = "auth:refresh:replay:${sha256Hex(presentedRefreshToken)}"
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (redisTemplate.hasKey(key)) {
            check(System.nanoTime() < deadline) { "재생 키가 5초 안에 만료되지 않았다" }
            Thread.sleep(20)
        }
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
        private val SHORT_GRACE: Duration = Duration.ofMillis(300)
        private val ACCESS_EXPIRES_AT: Instant = Instant.parse("2099-01-01T00:00:00Z")
        private val userIdSequence = AtomicLong(1_000_000_000_000L)
    }
}
