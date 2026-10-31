package com.jsm.boardgame.holdem.presentation.rest

import com.jsm.boardgame.holdem.application.command.usecase.ReserveNextDeckCommand
import com.jsm.boardgame.holdem.application.command.usecase.ReserveNextDeckUseCase
import com.jsm.boardgame.holdem.presentation.rest.request.NextDeckRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

// 인가는 SecurityConfig 의 "/api/admin/**" → hasRole("ADMIN") 이 처리한다.
// 카드 표기 오류는 CARD_NOTATION_INVALID(400), 그 밖의 잘못된 사양은 400 — ResponseStatusException 은
// GlobalExceptionHandler 가 errorCode=REQUEST_INVALID 로 만든다.
@RestController
@Profile("qa")
@RequestMapping("/api/admin/qa/holdem/tables")
class QaDeckController(private val reserveNextDeckUseCase: ReserveNextDeckUseCase) {

    @PostMapping("/{tableId}/next-deck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setNextDeck(@PathVariable tableId: Long, @RequestBody request: NextDeckRequest) {
        try {
            reserveNextDeckUseCase.reserve(ReserveNextDeckCommand(tableId, request.holeCards, request.board))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
    }
}
