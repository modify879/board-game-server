package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.user.application.command.usecase.AuthTokens
import com.jsm.boardgame.user.application.command.usecase.LoginUseCase
import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenCommand
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenUseCase
import com.jsm.boardgame.user.presentation.rest.request.LoginRequest
import com.jsm.boardgame.user.presentation.rest.response.TokenResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val REFRESH_TOKEN_COOKIE_NAME = "refresh_token"
private const val REFRESH_TOKEN_COOKIE_PATH = "/api/auth"

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val loginUseCase: LoginUseCase,
    private val refreshTokenUseCase: RefreshTokenUseCase,
    private val logoutUseCase: LogoutUseCase,
    private val clock: Clock,
) {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<TokenResponse> =
        withRefreshTokenCookie(loginUseCase.login(request.toCommand()))

    // 쿠키가 없거나 비어 있으면 빈 문자열로 넘긴다 — RefreshTokenUseCase 가 이미 알 수 없는
    // 토큰을 REFRESH_TOKEN_INVALID 로 거부하므로, 여기서 따로 예외를 만들지 않는다.
    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) refreshToken: String?,
    ): ResponseEntity<TokenResponse> =
        withRefreshTokenCookie(refreshTokenUseCase.refresh(RefreshTokenCommand(refreshToken.orEmpty())))

    @PostMapping("/logout")
    fun logout(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        // subject 가 숫자가 아니면(현재 발급기로는 도달 불가 — 토큰 출처가 늘거나 키가 약해질
        // 경우를 대비한 방어) NumberFormatException 이 그대로 새어나가 본문 없는 500 이 된다.
        // toLongOrNull() 로 받아 인증 실패(401 + 오류 계약)로 변환한다.
        val userId = jwt.subject?.toLongOrNull()
            ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=${jwt.subject}")
        logoutUseCase.logout(userId)
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, refreshTokenCookie("", Duration.ZERO).toString())
            .build()
    }

    private fun withRefreshTokenCookie(tokens: AuthTokens): ResponseEntity<TokenResponse> =
        ResponseEntity.ok()
            .header(
                HttpHeaders.SET_COOKIE,
                refreshTokenCookie(tokens.refreshToken, Duration.between(Instant.now(clock), tokens.refreshTokenExpiresAt)).toString(),
            )
            .body(TokenResponse.from(tokens))

    private fun refreshTokenCookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value)
            .path(REFRESH_TOKEN_COOKIE_PATH)
            .maxAge(maxAge)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .build()
}
