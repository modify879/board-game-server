package com.jsm.boardgame.user.presentation.rest

import com.jsm.boardgame.user.application.command.LoginUseCase
import com.jsm.boardgame.user.application.command.LogoutUseCase
import com.jsm.boardgame.user.application.command.RefreshTokenUseCase
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
        logoutUseCase.logout(checkNotNull(jwt.subject) { "인증된 JWT 는 subject 를 가져야 한다" }.toLong())
    }
}
