package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
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
 * 계산해 [MessageDigest.isEqual] 로 비교한다 — 이 메서드는 상수 시간 비교를 보장한다.
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
 * 유예는 오직 "바로 직전" 세대에만 적용된다 — 세션에는 previousRefreshToken 이 하나만
 * 저장되고 회전할 때마다 덮어써지므로, 두 세대 이상 전 토큰은 애초에 저장소에 남아 있지 않다.
 * 그런 토큰이 오면 유예 시각과 무관하게 `matchesRefreshToken` 이 false 를 돌려주고,
 * 호출자([RefreshTokenService])가 재사용 탐지로 세션 전체를 폐기한다.
 */
@Component
class RedisAuthSessionStore(
    private val redisTemplate: StringRedisTemplate,
    properties: JwtProperties,
) : AuthSessionStore {

    private val refreshReuseGrace: Duration = properties.refreshReuseGrace

    override fun start(userId: Long, session: AuthSession) {
        val ttl = Duration.between(Instant.now(), session.refreshTokenExpiresAt)
        if (ttl.isNegative || ttl.isZero) return

        val previousRefreshTokenHash = session.previousRefreshToken?.let { hash(it) } ?: ""
        val graceExpiresAtEpochMilli = if (session.previousRefreshToken != null) {
            Instant.now().plus(refreshReuseGrace).toEpochMilli()
        } else {
            0L
        }

        val value = listOf(
            session.accessTokenId,
            hash(session.refreshToken),
            previousRefreshTokenHash,
            graceExpiresAtEpochMilli.toString(),
        ).joinToString(FIELD_DELIMITER)

        redisTemplate.opsForValue().set(sessionKey(userId), value, ttl)
    }

    override fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean {
        val parsed = parseSession(userId) ?: return false
        val candidateHash = hash(refreshToken)

        if (constantTimeEquals(parsed.refreshTokenHash, candidateHash)) return true

        val previousRefreshTokenHash = parsed.previousRefreshTokenHash ?: return false
        if (!constantTimeEquals(previousRefreshTokenHash, candidateHash)) return false

        return Instant.now().isBefore(parsed.graceExpiresAt)
    }

    override fun currentAccessTokenId(userId: Long): String? =
        parseSession(userId)?.accessTokenId

    override fun clear(userId: Long) {
        redisTemplate.delete(sessionKey(userId))
    }

    override fun blacklistAccessToken(accessTokenId: String, expiresAt: Instant) {
        val ttl = Duration.between(Instant.now(), expiresAt)
        if (ttl.isNegative || ttl.isZero) return

        redisTemplate.opsForValue().set(blacklistKey(accessTokenId), BLACKLISTED_MARKER, ttl)
    }

    override fun isAccessTokenBlacklisted(accessTokenId: String): Boolean =
        redisTemplate.hasKey(blacklistKey(accessTokenId))

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

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        private const val SESSION_KEY_PREFIX = "auth:session:"
        private const val BLACKLIST_KEY_PREFIX = "auth:blacklist:"
        private const val FIELD_DELIMITER = ":"
        private const val BLACKLISTED_MARKER = "1"
    }
}
