package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.domain.model.Username
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Redis 기반 [LoginAttemptLimiter] 구현(`RedisLoginAttemptLimiter`) 통합 테스트.
 * 잠금 자체는 이제 DB(`User.lockedAt`)의 책임이라 여기서는 순수 카운터 동작만 검증한다.
 *
 * 테스트 간 격리는 각 테스트가 고유한 사용자명을 쓰는 방식으로 확보한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RedisLoginAttemptLimiterIntegrationTest {

    @Autowired
    private lateinit var limiter: LoginAttemptLimiter

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    private val window = Duration.ofMinutes(15)

    private fun uniqueUsername(): Username =
        Username.of("u" + UUID.randomUUID().toString().replace("-", "").take(10).lowercase())

    @Test
    fun `실패가 4번이면 아직 임계값 미만이다`() {
        val username = uniqueUsername()

        val results = (1..4).map { limiter.recordFailure(username, 5, window) }

        assertThat(results).allMatch { it == false }
    }

    @Test
    fun `5번째 실패는 true 를 돌려주고 카운터가 지워진다`() {
        val username = uniqueUsername()

        repeat(4) { limiter.recordFailure(username, 5, window) }
        val fifth = limiter.recordFailure(username, 5, window)

        assertThat(fifth).isTrue()
        assertThat(redisTemplate.hasKey("auth:login-fail:${username.value}")).isFalse()
    }

    @Test
    fun `첫 실패 후 카운터 키에 양수 PTTL 이 걸린다`() {
        val username = uniqueUsername()

        limiter.recordFailure(username, 5, window)

        val ttl = redisTemplate.getExpire("auth:login-fail:${username.value}", TimeUnit.MILLISECONDS)
        assertThat(ttl).isPositive()
    }

    @Test
    fun `reset 하면 카운터가 지워진다`() {
        val username = uniqueUsername()
        limiter.recordFailure(username, 5, window)

        limiter.reset(username)

        assertThat(redisTemplate.hasKey("auth:login-fail:${username.value}")).isFalse()
    }
}
