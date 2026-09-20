package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.user.application.command.usecase.LoginUseCase
import com.jsm.boardgame.user.application.command.usecase.LogoutUseCase
import com.jsm.boardgame.user.application.command.usecase.RefreshTokenUseCase
import com.jsm.boardgame.user.presentation.rest.request.LoginRequest
import com.jsm.boardgame.user.presentation.rest.request.RefreshTokenRequest
import com.jsm.boardgame.user.presentation.rest.response.TokenResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val loginUseCase: LoginUseCase,
    private val refreshTokenUseCase: RefreshTokenUseCase,
    private val logoutUseCase: LogoutUseCase,
) {

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): TokenResponse =
        TokenResponse.from(loginUseCase.login(request.toCommand()))

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshTokenRequest): TokenResponse =
        TokenResponse.from(refreshTokenUseCase.refresh(request.toCommand()))

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal jwt: Jwt) {
        // subject 가 숫자가 아니면(현재 발급기로는 도달 불가 — 토큰 출처가 늘거나 키가 약해질
        // 경우를 대비한 방어) NumberFormatException 이 그대로 새어나가 본문 없는 500 이 된다.
        // toLongOrNull() 로 받아 인증 실패(401 + 오류 계약)로 변환한다.
        val userId = jwt.subject?.toLongOrNull()
            ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=${jwt.subject}")
        logoutUseCase.logout(userId)
    }
}
