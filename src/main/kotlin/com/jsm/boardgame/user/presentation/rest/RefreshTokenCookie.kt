package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.presentation.rest.response.TokenResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val REFRESH_TOKEN_COOKIE_NAME = "refresh_token"
private const val REFRESH_TOKEN_COOKIE_PATH = "/api/auth"

/** 토큰을 본문으로, 리프레시 토큰을 쿠키로 내보내는 응답. 로그인·갱신·비밀번호 변경이 같은 모양이다. */
fun tokensResponse(tokens: AuthTokens, clock: Clock): ResponseEntity<TokenResponse> =
    ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            refreshTokenCookie(tokens.refreshToken, Duration.between(Instant.now(clock), tokens.refreshTokenExpiresAt)).toString(),
        )
        .body(TokenResponse.from(tokens, clock))

fun refreshTokenCookie(value: String, maxAge: Duration): ResponseCookie =
    ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value)
        .path(REFRESH_TOKEN_COOKIE_PATH)
        .maxAge(maxAge)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .build()
