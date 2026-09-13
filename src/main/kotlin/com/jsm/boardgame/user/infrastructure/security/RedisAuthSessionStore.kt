package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.application.port.AuthSession
import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * 단일 기기 정책의 인증 세션을 Redis 해시로 저장한다.
 *
 * 리프레시 토큰은 원문이 아니라 SHA-256 해시로 저장한다 — Redis 덤프가 유출돼도
 * 그 값을 그대로 리프레시 토큰으로 재사용할 수 없어야 한다. 대조는 해시를 같은 방식으로
 * 계산해 [MessageDigest.isEqual] 로 비교한다 — 이 메서드는 상수 시간 비교를 보장한다.
 *
 * TTL 은 만료 시각과 현재 시각의 차이로 계산한다. 이미 지난 시각이면 저장하지 않는다
 * (Redis 의 EXPIRE 는 음수 TTL 을 즉시 삭제로 처리하지만, 애초에 쓰지 않는 편이 의도가 분명하다).
 */
@Component
class RedisAuthSessionStore(
    private val redisTemplate: StringRedisTemplate,
) : AuthSessionStore {

    override fun start(userId: Long, session: AuthSession) {
        val ttl = Duration.between(Instant.now(), session.refreshTokenExpiresAt)
        if (ttl.isNegative || ttl.isZero) return

        val key = sessionKey(userId)
        redisTemplate.opsForHash<String, String>().putAll(
            key,
            mapOf(
                ACCESS_TOKEN_ID_FIELD to session.accessTokenId,
                REFRESH_TOKEN_HASH_FIELD to hash(session.refreshToken),
            ),
        )
        redisTemplate.expire(key, ttl)
    }

    override fun matchesRefreshToken(userId: Long, refreshToken: String): Boolean {
        val storedHash = redisTemplate.opsForHash<String, String>()
            .get(sessionKey(userId), REFRESH_TOKEN_HASH_FIELD)
            ?: return false

        return constantTimeEquals(storedHash, hash(refreshToken))
    }

    override fun currentAccessTokenId(userId: Long): String? =
        redisTemplate.opsForHash<String, String>().get(sessionKey(userId), ACCESS_TOKEN_ID_FIELD)

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
        private const val ACCESS_TOKEN_ID_FIELD = "accessTokenId"
        private const val REFRESH_TOKEN_HASH_FIELD = "refreshTokenHash"
        private const val BLACKLISTED_MARKER = "1"
    }
}
