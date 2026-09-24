package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.application.port.LoginAttemptLimiter
import com.jsm.boardgame.user.domain.model.Username
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 로그인 실패 횟수를 Redis 로 센다. 계정 잠금은 이 어댑터의 책임이 아니다 — `User.lockedAt`(DB, 영구)이
 * 실제 잠금 상태이고, 여기는 "몇 번 틀렸는지"만 짧게(고정 윈도) 기억하는 순수 카운터다.
 *
 * 키는 userId 가 아니라 정규화한 사용자명([Username.value])으로 만든다 — 그래야 아직 세션도 없는
 * 로그인 시도 단계에서도 셀 수 있다.
 *
 * 고정 윈도(fixed window)다 — 첫 실패에서 [window] 로 TTL 이 걸리고, 그 뒤 실패마다 창이 밀리지 않는다.
 *
 * [recordFailure] 는 INCR → (첫 실패면 PEXPIRE) → 임계값 도달 시 카운터 DEL 을 하나의 Lua 스크립트로
 * 묶는다. 나누면 "INCR 은 됐는데 PEXPIRE 전에 죽는" 중간 상태로 TTL 없는 카운터가 영구히 남거나,
 * 동시 요청이 임계값을 각자 넘겨 호출자(LoginService)가 같은 계정을 두 번 잠그려 들 수 있다
 * (User.lock() 이 멱등이라 실제 피해는 없지만, 카운터 DEL 은 어차피 한 번만 일어나야 한다).
 */
@Component
class RedisLoginAttemptLimiter(
    private val redisTemplate: StringRedisTemplate,
) : LoginAttemptLimiter {

    override fun recordFailure(username: Username, maxFailures: Int, window: Duration): Boolean {
        val result = redisTemplate.execute(
            RECORD_FAILURE_SCRIPT,
            listOf(failKey(username)),
            maxFailures.toString(),
            window.toMillis().toString(),
        )
        return result == 1L
    }

    override fun reset(username: Username) {
        redisTemplate.delete(failKey(username))
    }

    private fun failKey(username: Username): String = "$FAIL_KEY_PREFIX${username.value}"

    companion object {
        private const val FAIL_KEY_PREFIX = "auth:login-fail:"

        /**
         * KEYS[1] = 실패 카운터 키
         * ARGV[1] = 최대 허용 실패 횟수
         * ARGV[2] = 실패 윈도(밀리초, 문자열) — 첫 실패에서만 TTL 을 건다(고정 윈도)
         *
         * 카운터를 증가시키고, 첫 실패면 윈도로 TTL 을 건다. 이번 실패로 임계값에 도달했으면
         * 카운터를 지우고 1 을 돌려준다. 아니면 0.
         */
        private val RECORD_FAILURE_SCRIPT: RedisScript<Long> = DefaultRedisScript(
            """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if n >= tonumber(ARGV[1]) then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
