package com.jsm.boardgame.user.infrastructure.security.adapter

import com.jsm.boardgame.user.application.port.RotationResult
import com.jsm.boardgame.user.domain.model.UserRole
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 재생 레코드(회전이 발급한 후속 토큰)를 암호화한다. 키는 **제시된(밀려난) 리프레시 토큰**에서만
 * 유도한다 — Redis 를 읽을 수 있는 사람은 해시(인덱스·직전 칸)만 알 뿐 토큰 원문이 없어 복호화할 수 없다.
 * 키는 SHA-256(토큰) 이 아니다(그 값은 Redis 에 저장된다). HKDF-SHA256 에 도메인 분리 라벨을 넣어 유도한다.
 */
internal object RefreshReplayCipher {
    private const val SEPARATOR = "|" // base64url·JWT·UUID·ISO-8601 Instant 어디에도 나오지 않는다
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val salt = "board-game/refresh-replay/salt/v1".toByteArray(Charsets.UTF_8)
    private val info = "board-game/refresh-replay/v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    fun encrypt(presentedRefreshToken: String, successor: RotationResult.Replayed): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(presentedRefreshToken), GCMParameterSpec(TAG_BITS, iv))
        val plain = listOf(
            successor.accessTokenId,
            successor.accessToken,
            successor.refreshToken,
            successor.accessTokenExpiresAt.toString(),
            successor.refreshTokenExpiresAt.toString(),
            successor.role.name,
        ).joinToString(SEPARATOR)
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 복호화·파싱 실패(다른 토큰, 변조)는 null. */
    fun decrypt(presentedRefreshToken: String, payload: String): RotationResult.Replayed? = runCatching {
        val raw = Base64.getDecoder().decode(payload)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(presentedRefreshToken), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        val parts = String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8).split(SEPARATOR)
        RotationResult.Replayed(
            accessTokenId = parts[0],
            accessToken = parts[1],
            refreshToken = parts[2],
            accessTokenExpiresAt = Instant.parse(parts[3]),
            refreshTokenExpiresAt = Instant.parse(parts[4]),
            role = UserRole.valueOf(parts[5]),
        )
    }.getOrNull()

    // HKDF(RFC 5869) extract + expand, 출력 32바이트 = HMAC 한 블록(T(1))이라 expand 가 한 번이다.
    private fun key(token: String): SecretKeySpec {
        val prk = hmac(salt, token.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(hmac(prk, info + 1.toByte()), "AES")
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
