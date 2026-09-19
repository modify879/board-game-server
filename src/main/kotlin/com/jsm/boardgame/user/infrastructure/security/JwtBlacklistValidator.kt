package com.jsm.boardgame.user.infrastructure.security

import com.jsm.boardgame.user.application.port.AuthSessionStore
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 로그아웃되거나 단일 기기 정책으로 교체된 액세스 토큰은 서명·만료 검증을 통과해도
 * 더 이상 유효하지 않다. 표준 검증 파이프라인(`JwtDecoder`) 안에 넣어야 요청마다
 * 별도 필터로 같은 예외 처리를 다시 만들지 않아도 된다.
 *
 * jti(`jwt.id`)로 [AuthSessionStore.isAccessTokenBlacklisted] 를 조회한다.
 */
class JwtBlacklistValidator(
    private val sessions: AuthSessionStore,
) : OAuth2TokenValidator<Jwt> {

    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        val tokenId = token.id
        if (tokenId != null && sessions.isAccessTokenBlacklisted(tokenId)) {
            return OAuth2TokenValidatorResult.failure(
                OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "access token is blacklisted", null),
            )
        }
        return OAuth2TokenValidatorResult.success()
    }
}
