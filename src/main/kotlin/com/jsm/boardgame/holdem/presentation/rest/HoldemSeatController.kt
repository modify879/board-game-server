package com.jsm.boardgame.holdem.presentation.rest

import com.jsm.boardgame.common.error.AuthenticationRequiredException
import com.jsm.boardgame.holdem.application.command.usecase.StandUpCommand
import com.jsm.boardgame.holdem.application.command.usecase.StandUpUseCase
import com.jsm.boardgame.holdem.application.query.service.MySeatQueryService
import com.jsm.boardgame.holdem.presentation.rest.response.MySeatResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// 한 사람은 한 자리이므로 기립은 tableId 없이 유저로 유일하게 찾힌다.
@RestController
@RequestMapping("/api/holdem")
class HoldemSeatController(
    private val standUpUseCase: StandUpUseCase,
    private val mySeatQueryService: MySeatQueryService,
) {

    @DeleteMapping("/seat")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun standUp(@AuthenticationPrincipal jwt: Jwt) {
        standUpUseCase.standUp(StandUpCommand(jwt.requireUserId()))
    }

    // 이 조회는 언제나 허용된다 (MySeatQueryService KDoc 참고) — 여기에 차단 로직을 얹지 않는다.
    @GetMapping("/me/seat")
    fun mySeat(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<MySeatResponse> {
        val view = mySeatQueryService.findMine(jwt.requireUserId()) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(MySeatResponse.from(view))
    }
}

// AuthController.logout(), WalletController 와 같은 방식이다 — subject 가 숫자로 파싱되지 않으면 401(오류 계약)로 변환한다.
private fun Jwt.requireUserId(): Long =
    subject?.toLongOrNull()
        ?: throw AuthenticationRequiredException("인증된 JWT 의 subject 를 사용자 식별자로 파싱할 수 없다: subject=$subject")
