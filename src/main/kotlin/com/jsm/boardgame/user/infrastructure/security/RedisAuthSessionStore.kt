package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import com.jsm.boardgame.user.application.port.RotationResult
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 단일 기기 정책의 인증 세션을 Redis 문자열 값 하나로 저장한다.
 *
 * 세션은 `"$accessTokenId:$refreshTokenHash:$previousRefreshTokenHash:$graceExpiresAtEpochMilli"`
 * 형태의 단일 문자열로 직렬화해 `SET key value EX ttl`
 * (자바 클라이언트에서는 [org.springframework.data.redis.core.ValueOperations.set] 의 TTL 오버로드)
 * 한 번으로 저장한다. Redis 의 SET 은 값 대입과 TTL 설정을 한 명령으로 처리하는 원자적 연산이라
 * "값은 썼는데 TTL 은 못 붙인" 중간 상태가 존재하지 않는다.
 *
 * 예전에는 Redis 해시에 필드들을 [putAll] 로 채운 뒤 별도로 [expire] 를 거는 두 번의 왕복이었다.
 * 그 사이에 프로세스가 죽거나 커넥션이 끊기면 TTL 이 없는 세션 키가 영구히 Redis 에 남는다 —
 * 사용자가 다시 로그인하거나 로그아웃해서 [clear]/[start] 로 키를 덮어쓰기 전까지 정리되지 않는
 * 고아 키 누적 결함이었다. 그래서 반드시 한 번의 명령으로 값과 TTL 을 함께 확정해야 한다.
 *
 * 필드 구분자 `:` 는 네 필드 중 어디에도 나타나지 않는다 — accessTokenId(UUID: 16진수와 `-`),
 * refreshTokenHash/previousRefreshTokenHash(SHA-256 16진수, 직전 토큰이 없으면 빈 문자열),
 * graceExpiresAtEpochMilli(10진수, 직전 토큰이 없으면 "0") 모두 `:` 를 포함할 수 없는 문자 집합이다.
 * 그래서 `split(":", limit = 4)` 로 안전하게 되돌릴 수 있다.
 *
 * 리프레시 토큰은 원문이 아니라 SHA-256 해시로 저장한다 — Redis 덤프가 유출돼도
 * 그 값을 그대로 리프레시 토큰으로 재사용할 수 없어야 한다. 대조는 해시를 같은 방식으로
 * 계산해 회전 스크립트 안에서 한다.
 *
 * TTL 은 만료 시각과 현재 시각의 차이로 계산한다. 이미 지난 시각이면 저장하지 않는다
 * (Redis 의 EXPIRE 는 음수 TTL 을 즉시 삭제로 처리하지만, 애초에 쓰지 않는 편이 의도가 분명하다).
 *
 * ### 리프레시 토큰 회전 유예
 *
 * 회전 응답이 네트워크에서 유실되면 클라이언트는 이미 무효화된 옛 토큰으로 재시도한다.
 * 이를 탈취로 오판해 세션을 폐기하면 정상 사용자가 강제 로그아웃된다. 그래서 직전 토큰
 * 한 세대에 한해 [JwtProperties.refreshReuseGrace] 동안만 재사용을 허용한다.
 *
 * 유예는 오직 "바로 직전" 세대에만 적용된다 — 세션에는 직전 토큰 해시가 하나만 저장되고
 * 회전할 때마다 덮어써지므로, 두 세대 이상 전 토큰은 애초에 저장소에 남아 있지 않다.
 * 그런 토큰이 오면 유예 시각과 무관하게 [rotate] 가 [RotationResult.Mismatch] 를 돌려주고,
 * 호출자([RefreshTokenService])가 재사용 탐지로 세션 전체를 폐기한다.
 *
 * **직전 칸에 무엇이 들어가는가는 이 저장소가 정한다 — 호출자가 정하지 않는다.** [rotate] 가
 * 성공적으로 교체할 때, 새 직전 칸에는 언제나 "이번 회전으로 밀려난 현재 토큰"이 들어간다.
 * 제시된 토큰이 현재 칸과 일치해 통과했든 유예 중인 직전 칸과 일치해 통과했든 상관없다.
 * 만약 호출자가 "이번에 제시된 토큰"을 직전 칸에 넣는 식으로 구현했다면, 유예 중인 직전
 * 토큰을 반복 제시할 때마다 그 토큰이 계속 직전 칸에 재기록되어 유예가 사실상 무한정
 * 갱신되고, 동시에 실제로 밀려난 현재 토큰은 두 칸 어디에도 남지 않아 그 토큰을 들고 있던
 * 정상 클라이언트가 재사용 탐지로 세션째 폐기당한다. [AuthSession] 에 previousRefreshToken
 * 필드가 없는 이유가 이것이다 — 호출자가 애초에 그 값을 정할 수 없어야 이 실수가 불가능하다.
 * 그래서 [ROTATE_SCRIPT] 가 현재 해시(교체되기 직전 값)를 직접 읽어 다음 직전 칸에 넣는다.
 *
 * ### 동시 갱신 경합과 원자적 회전
 *
 * 리프레시 토큰이 일치하는지 확인한 뒤 별도로 [start] 를 호출하는 "확인 후 실행"은 원자적이지
 * 않다. 같은 리프레시 토큰으로 두 요청이 동시에 오면 둘 다 확인을 통과하고, 각자 새 토큰을
 * 발급한 뒤 나중에 SET 하는 쪽이 이겨 먼저 응답받은 클라이언트는 저장소에 없는 토큰을 들고
 * 남는다. [rotate] 는 확인과 교체를 Lua 스크립트로 묶어 Redis 서버에서 한 번에 원자적으로
 * 처리해 이 경합을 없앤다. 해시 계산과 현재 시각·TTL 계산은 스크립트가 아니라 여기(Kotlin)에서
 * 하고 결과만 인자로 넘긴다 — 원문 토큰이 Redis 로 전달되지 않게 하고, 스크립트가 직접 시계를
 * 읽어 테스트 재현성이 떨어지는 것을 막기 위해서다. 다음 직전 칸에 들어갈 값(밀려난 현재
 * 해시)만은 예외로, 스크립트가 방금 읽은 현재 값에서 직접 뽑아 조립한다 — 그래야 호출자가
 * 끼어들 여지가 없다.
 *
 * ### 즉시 경합 가드 (MIN_GRACE_ELAPSED_MILLIS)
 *
 * 원자성만으로는 충분하지 않다. 두 요청이 정말 동시에 같은(아직 회전되지 않은) 토큰을 제시하면,
 * Redis 가 두 Lua 실행을 직렬화하므로 그중 하나만 "현재 토큰과 일치"로 통과한다 — 그런데
 * 나머지 하나는, 방금 그 하나가 연 유예 창 안에서 "직전 토큰과 일치"로 **똑같이 통과해버린다**.
 * 유예는 원래 "응답 유실 후 나중에 재시도"를 위한 것이지만, 저장된 값만 보면 "몇 초 뒤의 재시도"와
 * "몇 마이크로초 뒤의 동시 경합"을 구별할 수 없다. 방치하면 진 쪽도 200 과 함께 유효해 보이는
 * 토큰을 받고, 그 액세스 토큰마저 이긴 쪽의 다음 회전에 딸려 블랙리스트에 오르며, 이후 그 고아
 * 토큰이 제시되면 재사용 탐지로 오인돼 이긴 쪽의 정상 세션까지 강제 로그아웃된다 — 없앤 줄 알았던
 * 경합이 유예 경로로 되살아나는 셈이다.
 *
 * 그래서 유예 통과 조건에 "이 유예 창이 열린 뒤 [MIN_GRACE_ELAPSED_MILLIS] 이상 지났는가"를
 * 추가한다. 실제 클라이언트의 응답 유실 재시도는 최소 한 번의 요청 타임아웃(보통 수백 ms~수 초)
 * 이후에나 일어나므로 전혀 영향받지 않는다. 반면 같은 Redis 인스턴스에서 두 Lua 스크립트가
 * 등에 업혀 실행되는 간격은 마이크로초~저수 ms 수준이라 이 가드에 안정적으로 걸린다. 이 값은
 * 인스턴스마다 달라지는 [refreshReuseGrace] 와 달리 순수한 구현 상수라 스크립트 텍스트에
 * 직접 새겨 넣는다(요청마다 인자로 넘길 이유가 없다).
 *
 * ### 두 장치는 중복이 아니다
 *
 * 이 가드와 "직전 칸 = 밀려난 현재 토큰" 규칙은 **서로 다른 실패를 막는다.**
 * 가드는 동시 중복(마이크로초 간격)을 막고, 직전 칸 규칙은 유예 토큰이 매번 새 유예 창을
 * 얻어 무한히 되살아나는 것을 막는다. 하나만 보고 다른 하나를 중복이라 판단해 지우지 마라 —
 * 실제로 이 저장소에서 "둘 중 하나를 고르는 문제"로 잘못 보고 후자를 빠뜨렸다가,
 * 유예 토큰이 영구히 유효해지고 정상 클라이언트만 쫓겨나는 버그를 만들었다.
 */
@Component
class RedisAuthSessionStore(
    private val redisTemplate: StringRedisTemplate,
    properties: JwtProperties,
    // Instant.now() 를 직접 부르지 않고 주입받는다 — 테스트가 시간을 제어할 수 있어야 하기 때문이다.
    private val clock: Clock,
) : AuthSessionStore {

    private val refreshReuseGrace: Duration = properties.refreshReuseGrace

    override fun start(userId: Long, session: AuthSession) {
        val ttl = Duration.between(Instant.now(clock), session.refreshTokenExpiresAt)
        if (ttl.isNegative || ttl.isZero) return

        redisTemplate.opsForValue().set(sessionKey(userId), serialize(session), ttl)
    }

    override fun currentAccessTokenId(userId: Long): String? =
        parseSession(userId)?.accessTokenId

    override fun clear(userId: Long) {
        redisTemplate.delete(sessionKey(userId))
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(clock), expiresAt)
        if (ttl.isNegative || ttl.isZero) return

        redisTemplate.opsForValue().set(blacklistKey(accessTokenId), BLACKLISTED_MARKER, ttl)
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean =
        redisTemplate.hasKey(blacklistKey(accessTokenId))

    override fun rotate(userId: Long, presentedRefreshToken: String, next: AuthSession): RotationResult {
        val now = Instant.now(clock)
        val ttl = Duration.between(now, next.refreshTokenExpiresAt)
        // tokenIssuer 는 항상 미래 만료 시각의 토큰을 발급하므로 실제로는 항상 양수다 — SET 의 EX 에
        // 0 이하를 넘기면 Redis 가 에러를 내므로 방어적으로 최소 1초를 보장한다.
        val ttlSeconds = ttl.seconds.coerceAtLeast(1)
        val graceExpiresAtEpochMilli = now.plus(refreshReuseGrace).toEpochMilli()

        val previousAccessTokenId = redisTemplate.execute(
            ROTATE_SCRIPT,
            listOf(sessionKey(userId)),
            hash(presentedRefreshToken),
            now.toEpochMilli().toString(),
            next.accessTokenId,
            hash(next.refreshToken),
            ttlSeconds.toString(),
            refreshReuseGrace.toMillis().toString(),
            graceExpiresAtEpochMilli.toString(),
        )

        return if (previousAccessTokenId != null) {
            RotationResult.Rotated(previousAccessTokenId)
        } else {
            RotationResult.Mismatch
        }
    }

    /**
     * 세션을 저장 문자열 포맷(`accessTokenId:hash:prevHash:graceMillis`)으로 직렬화한다.
     * [start] 전용이다 — 직전 칸을 늘 비운다(로그인은 새 세션이므로 유예 대상이 없다).
     * [rotate] 는 이 함수를 쓰지 않는다: 다음 직전 칸에 "밀려난 현재 해시"를 넣어야 하는데
     * 그건 Redis 에 이미 저장된 값에서만 알 수 있으므로 [ROTATE_SCRIPT] 가 직접 조립한다.
     */
    private fun serialize(session: AuthSession): String {
        return listOf(
            session.accessTokenId,
            hash(session.refreshToken),
            "",
            "0",
        ).joinToString(FIELD_DELIMITER)
    }

    /** 세션 값을 필드로 분리한다. 세션이 없거나 형식이 깨졌으면 null. */
    private fun parseSession(userId: Long): ParsedSession? {
        val value = redisTemplate.opsForValue().get(sessionKey(userId)) ?: return null
        val parts = value.split(FIELD_DELIMITER, limit = 4)
        if (parts.size != 4) return null

        val graceExpiresAtEpochMilli = parts[3].toLongOrNull() ?: return null

        return ParsedSession(
            accessTokenId = parts[0],
            refreshTokenHash = parts[1],
            previousRefreshTokenHash = parts[2].ifEmpty { null },
            graceExpiresAt = Instant.ofEpochMilli(graceExpiresAtEpochMilli),
        )
    }

    private data class ParsedSession(
        val accessTokenId: String,
        val refreshTokenHash: String,
        val previousRefreshTokenHash: String?,
        val graceExpiresAt: Instant,
    )

    private fun sessionKey(userId: Long): String = "$SESSION_KEY_PREFIX$userId"

    private fun blacklistKey(accessTokenId: String): String = "$BLACKLIST_KEY_PREFIX$accessTokenId"

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val SESSION_KEY_PREFIX = "auth:session:"
        private const val BLACKLIST_KEY_PREFIX = "auth:blacklist:"
        private const val FIELD_DELIMITER = ":"
        private const val BLACKLISTED_MARKER = "1"

        /** [rotate] 의 즉시 경합 가드. 클래스 docs "즉시 경합 가드" 절 참고. */
        private const val MIN_GRACE_ELAPSED_MILLIS = 100L

        /**
         * KEYS[1] = 세션 키
         * ARGV[1] = 제시된 리프레시 토큰의 SHA-256 해시
         * ARGV[2] = 현재 시각(epoch millis, 문자열) — Kotlin 이 계산해 넘긴다
         * ARGV[3] = 다음 세션의 accessTokenId
         * ARGV[4] = 다음 세션의 리프레시 토큰 SHA-256 해시
         * ARGV[5] = 다음 세션의 TTL(초, 문자열)
         * ARGV[6] = 이 저장소 인스턴스의 [refreshReuseGrace](밀리초, 문자열) — 즉시 경합 가드 계산용
         * ARGV[7] = 다음 세션의 유예 만료 시각(epoch millis, 문자열) — Kotlin 이 now + refreshReuseGrace 로 계산해 넘긴다
         *
         * 저장된 값이 없으면 nil(불일치). 있으면 `accessTokenId:hash:prevHash:graceMillis` 로 나눠
         * 제시된 해시가 현재 해시와 같으면 곧바로 통과. 아니면 직전 해시와 같고 아직 유예 시각
         * 이전이면서, 그 유예 창이 열린 지 [MIN_GRACE_ELAPSED_MILLIS] 이상 지났을 때만 통과시킨다
         * (즉시 경합 가드 — 클래스 docs 참고).
         *
         * 통과하면 다음 값을 **스크립트가 직접 조립해서** SET 한다:
         * `ARGV[3]:ARGV[4]:hash:ARGV[7]` — 여기서 `hash` 는 방금 이 스크립트가 GET 으로 읽은,
         * 교체되기 직전의 "현재" 해시다. 즉 다음 직전 칸에는 제시된 해시(ARGV[1])가 아니라
         * 이번 회전으로 밀려난 현재 해시가 들어간다 — 제시된 토큰이 현재 칸과 일치해서
         * 통과했든 유예 중인 직전 칸과 일치해서 통과했든 동일하다. 교체 전 accessTokenId 를
         * 돌려준다. 그 외에는 nil.
         *
         * GET 부터 SET 까지가 단일 Lua 스크립트 실행 안에서 끝나므로(Redis 는 스크립트 실행 중
         * 다른 명령을 끼워 넣지 않는다) 동시에 들어온 두 rotate 호출 중 하나만 통과한다.
         */
        private val ROTATE_SCRIPT: RedisScript<String> = DefaultRedisScript(
            """
            local current = redis.call('GET', KEYS[1])
            if not current then
              return nil
            end

            local sep1 = string.find(current, ':', 1, true)
            if not sep1 then return nil end
            local sep2 = string.find(current, ':', sep1 + 1, true)
            if not sep2 then return nil end
            local sep3 = string.find(current, ':', sep2 + 1, true)
            if not sep3 then return nil end

            local accessTokenId = string.sub(current, 1, sep1 - 1)
            local hash = string.sub(current, sep1 + 1, sep2 - 1)
            local prevHash = string.sub(current, sep2 + 1, sep3 - 1)
            local graceMillis = tonumber(string.sub(current, sep3 + 1))

            local presentedHash = ARGV[1]
            local nowMillis = tonumber(ARGV[2])
            local refreshReuseGraceMillis = tonumber(ARGV[6])

            local matched = false
            if presentedHash == hash then
              matched = true
            elseif prevHash ~= '' and prevHash == presentedHash and graceMillis and graceMillis > nowMillis then
              local elapsedSinceRotationMillis = refreshReuseGraceMillis - (graceMillis - nowMillis)
              if elapsedSinceRotationMillis >= $MIN_GRACE_ELAPSED_MILLIS then
                matched = true
              end
            end

            if not matched then
              return nil
            end

            local newAccessTokenId = ARGV[3]
            local newHash = ARGV[4]
            local ttlSeconds = ARGV[5]
            local newGraceExpiresAt = ARGV[7]

            -- 다음 직전 칸은 언제나 "이번에 밀려난 현재 해시"(hash)다 — 제시된 해시가 아니다.
            local nextValue = newAccessTokenId .. ':' .. newHash .. ':' .. hash .. ':' .. newGraceExpiresAt
            redis.call('SET', KEYS[1], nextValue, 'EX', ttlSeconds)
            return accessTokenId
            """.trimIndent(),
            String::class.java,
        )
    }
}
