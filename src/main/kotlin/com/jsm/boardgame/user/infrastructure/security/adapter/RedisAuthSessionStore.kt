package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.infrastructure.security.config.JwtProperties
import com.jsm.boardgame.user.domain.model.UserId
import com.jsm.boardgame.user.domain.model.UserRole
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
 * 필드별로 SET 한 뒤 별도로 EXPIRE 를 거는 두 번의 왕복으로 구현하면, 그 사이에 프로세스가 죽거나
 * 커넥션이 끊길 때 TTL 이 없는 세션 키가 영구히 Redis 에 남는다 — 사용자가 다시 로그인하거나
 * 로그아웃해서 [clear]/[start] 로 키를 덮어쓰기 전까지 정리되지 않는 고아 키가 된다. 그래서 반드시
 * 한 번의 명령으로 값과 TTL 을 함께 확정해야 한다.
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
 * ### 리프레시 토큰 회전과 멱등 재생
 *
 * 쿠키 하나를 여러 요청이 공유하므로 같은 리프레시 토큰이 동시에(또는 응답 유실 뒤 재시도로) 다시 온다.
 * 이를 탈취로 보고 세션을 폐기하면 정상 사용자가 로그아웃된다. 그래서 직전 토큰 한 세대에 한해
 * [JwtProperties.refreshReuseGrace] 동안은 **다시 회전하지 않고, 처음 회전이 발급한 후속 토큰을 그대로 돌려준다**
 * ([RotationResult.Replayed]). 아무것도 블랙리스트에 오르거나 닫히지 않는다.
 *
 * 정상 회전([ROTATE_SCRIPT] 에서 제시 해시 == 현재 해시) 때 스크립트가 세션을 교체하면서, 같은 실행 안에서
 * `auth:refresh:replay:{밀려난 해시}` 에 암호화된 후속 토큰을 유예 길이만큼만 둔다. 유예 안에 직전 해시가 오면
 * 그 값을 돌려주고, 레코드가 없으면 nil([RotationResult.Mismatch])이다. 확인·교체·기록이 한 Lua 실행이라
 * 동시에 N 개가 와도 하나만 Rotated 이고 나머지는 같은 값의 Replayed 다. 해시·시각·TTL 계산과 암호화는
 * 스크립트가 아니라 여기(Kotlin)에서 하고 결과만 인자로 넘긴다 — 원문 토큰이 Redis 로 가지 않고 시계를
 * 테스트가 조작할 수 있다. 다음 직전 칸에 들어갈 값(밀려난 현재 해시)만은 스크립트가 방금 읽은 값에서 직접 조립한다.
 *
 * **후속 토큰은 암호화해 둔다.** 평문으로 두면 Redis 를 읽을 수 있는 쪽(덤프·백업·운영자)이 유예 5초 동안
 * 살아 있는 리프레시 토큰을 얻는다. 키는 제시된 옛 리프레시 토큰에서 HKDF 로 유도한다([RefreshReplayCipher]) —
 * 그 토큰을 가진 쪽만 풀 수 있고, Redis 에는 그 해시만 있어 풀 수 없다. 복호화 실패는 Mismatch 다.
 *
 * 유예가 지난 뒤의 직전 토큰, 두 세대 이상 전 토큰(세션엔 직전 해시 하나뿐이고 회전마다 덮어써진다)은
 * 모두 Mismatch 이고 호출자([RefreshTokenService])가 재사용 탐지로 세션 전체를 폐기한다. 유예 안에서
 * 옛 토큰을 가진 공격자가 얻는 것은 정상 클라이언트가 받은 것과 같은 후속 토큰이라, 재생이 이전 동작(다시 회전)보다
 * 공격 면을 넓히지 않는다. 인덱스(`auth:refresh:{sha256}`)는 회전해도 지우지 않는다 — 재사용 탐지용이다.
 *
 * 경과 시간으로 재생을 막는 가드를 다시 넣지 마라 — 동시 요청을 Mismatch 로 만들어 정상 사용자를 쫓아낸다.
 */
@Component
class RedisAuthSessionStore(
    private val redisTemplate: StringRedisTemplate,
    properties: JwtProperties,
    private val clock: Clock,
) : AuthSessionStore {

    private val refreshReuseGrace: Duration = properties.refreshReuseGrace

    override fun start(userId: UserId, session: AuthSession) {
        val ttl = Duration.between(Instant.now(clock), session.refreshTokenExpiresAt)
        if (ttl.isNegative || ttl.isZero) return

        redisTemplate.opsForValue().set(sessionKey(userId), serialize(session), ttl)
        redisTemplate.opsForValue().set(refreshIndexKey(hash(session.refreshToken)), userId.value.toString(), ttl)
    }

    override fun currentAccessTokenId(userId: UserId): String? =
        parseSession(userId)?.accessTokenId

    override fun clear(userId: UserId) {
        redisTemplate.delete(sessionKey(userId))
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(clock), expiresAt)
        if (ttl.isNegative || ttl.isZero) return

        redisTemplate.opsForValue().set(blacklistKey(accessTokenId), BLACKLISTED_MARKER, ttl)
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean =
        redisTemplate.hasKey(blacklistKey(accessTokenId))

    override fun rotate(
        userId: UserId,
        presentedRefreshToken: String,
        next: AuthSession,
        nextAccessToken: String,
        nextAccessTokenExpiresAt: Instant,
        nextRole: UserRole,
    ): RotationResult {
        val now = Instant.now(clock)
        val ttl = Duration.between(now, next.refreshTokenExpiresAt)
        // tokenIssuer 는 항상 미래 만료 시각의 토큰을 발급하므로 실제로는 항상 양수다 — SET 의 EX 에
        // 0 이하를 넘기면 Redis 가 에러를 내므로 방어적으로 최소 1초를 보장한다.
        val ttlSeconds = ttl.seconds.coerceAtLeast(1)
        val graceExpiresAtEpochMilli = now.plus(refreshReuseGrace).toEpochMilli()
        val nextRefreshTokenHash = hash(next.refreshToken)
        val presentedHash = hash(presentedRefreshToken)
        val replayPayload = RefreshReplayCipher.encrypt(
            presentedRefreshToken,
            RotationResult.Replayed(
                accessTokenId = next.accessTokenId,
                accessToken = nextAccessToken,
                refreshToken = next.refreshToken,
                accessTokenExpiresAt = nextAccessTokenExpiresAt,
                refreshTokenExpiresAt = next.refreshTokenExpiresAt,
                role = nextRole,
            ),
        )

        val result = redisTemplate.execute(
            ROTATE_SCRIPT,
            listOf(sessionKey(userId), replayKey(presentedHash)),
            presentedHash,
            now.toEpochMilli().toString(),
            next.accessTokenId,
            nextRefreshTokenHash,
            ttlSeconds.toString(),
            graceExpiresAtEpochMilli.toString(),
            replayPayload,
            (refreshReuseGrace.toMillis() + REPLAY_TTL_MARGIN_MILLIS).toString(),
        )
        val kind = result?.getOrNull(0) as String?
        val value = result?.getOrNull(1) as String?

        return when (kind) {
            "R" -> {
                redisTemplate.opsForValue().set(
                    refreshIndexKey(nextRefreshTokenHash),
                    userId.value.toString(),
                    Duration.ofSeconds(ttlSeconds),
                )
                RotationResult.Rotated(value)
            }
            "P" -> RefreshReplayCipher.decrypt(presentedRefreshToken, value ?: "") ?: RotationResult.Mismatch
            else -> RotationResult.Mismatch
        }
    }

    override fun userIdForRefreshToken(refreshToken: String): UserId? =
        redisTemplate.opsForValue().get(refreshIndexKey(hash(refreshToken)))?.toLongOrNull()?.let(::UserId)

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

    private fun parseSession(userId: UserId): ParsedSession? {
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

    private fun sessionKey(userId: UserId): String = "$SESSION_KEY_PREFIX${userId.value}"

    private fun blacklistKey(accessTokenId: String): String = "$BLACKLIST_KEY_PREFIX$accessTokenId"

    private fun refreshIndexKey(tokenHash: String): String = "$REFRESH_INDEX_KEY_PREFIX$tokenHash"

    private fun replayKey(tokenHash: String): String = "$REPLAY_KEY_PREFIX$tokenHash"

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    companion object {
        private const val SESSION_KEY_PREFIX = "auth:session:"
        private const val BLACKLIST_KEY_PREFIX = "auth:blacklist:"
        private const val REFRESH_INDEX_KEY_PREFIX = "auth:refresh:"
        private const val FIELD_DELIMITER = ":"
        private const val BLACKLISTED_MARKER = "1"

        private const val REPLAY_KEY_PREFIX = "auth:refresh:replay:"

        /** 재생 레코드 TTL 여유 — 유예 판정은 세션 값의 시각이 하고, 이건 레코드가 먼저 사라지지 않게 하는 안전 마진이다. */
        private const val REPLAY_TTL_MARGIN_MILLIS = 1_000L

        /**
         * KEYS[1] = 세션 키, KEYS[2] = 제시된 해시의 재생 키
         * ARGV[1] = 제시된 리프레시 토큰의 SHA-256 해시
         * ARGV[2] = 현재 시각(epoch millis) — Kotlin 이 계산해 넘긴다
         * ARGV[3] = 다음 세션의 accessTokenId
         * ARGV[4] = 다음 세션의 리프레시 토큰 SHA-256 해시
         * ARGV[5] = 다음 세션의 TTL(초)
         * ARGV[6] = 다음 세션의 유예 만료 시각(epoch millis)
         * ARGV[7] = 암호화된 재생 페이로드
         * ARGV[8] = 재생 키 TTL(밀리초)
         *
         * 반환: nil(불일치) / {"R", 교체 전 accessTokenId}(회전) / {"P", 재생 페이로드}(유예 안의 직전 토큰).
         *
         * 제시 해시 == 현재 해시면 세션을 `ARGV[3]:ARGV[4]:hash:ARGV[6]` 로 교체하고(다음 직전 칸 = 밀려난 현재 해시)
         * 재생 키에 ARGV[7] 을 PX 로 둔다. 제시 해시 == 직전 해시이고 유예 시각 전이면 재생 키 값을 돌려준다
         * (없으면 nil). GET 부터 SET 까지가 단일 Lua 실행이라 동시에 온 호출 중 하나만 회전한다.
         */
        private val ROTATE_SCRIPT: RedisScript<List<*>> = DefaultRedisScript(
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

            if presentedHash == hash then
              local nextValue = ARGV[3] .. ':' .. ARGV[4] .. ':' .. hash .. ':' .. ARGV[6]
              redis.call('SET', KEYS[1], nextValue, 'EX', ARGV[5])
              redis.call('SET', KEYS[2], ARGV[7], 'PX', ARGV[8])
              return {'R', accessTokenId}
            end

            if prevHash ~= '' and prevHash == presentedHash and graceMillis and graceMillis > nowMillis then
              local replay = redis.call('GET', KEYS[2])
              if replay then
                return {'P', replay}
              end
            end
            return nil
            """.trimIndent(),
            List::class.java,
        )
    }
}
