package com.jsm.boardgame.holdem.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.holdem.application.command.usecase.ResetQaAccountsCommand
import com.jsm.boardgame.holdem.application.command.usecase.ResetQaAccountsUseCase
import com.jsm.boardgame.holdem.presentation.rest.request.QaResetRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 인가는 SecurityConfig 의 "/api/admin/**" → hasRole("ADMIN") 이 처리한다.
@RestController
@Profile("qa")
@RequestMapping("/api/admin/qa")
class QaResetController(private val resetQaAccountsUseCase: ResetQaAccountsUseCase) {

    @PostMapping("/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: QaResetRequest) {
        val adminId = jwt.subject?.toLongOrNull()
            ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=${jwt.subject}")
        resetQaAccountsUseCase.reset(ResetQaAccountsCommand(request.usernames, adminId))
    }
}
