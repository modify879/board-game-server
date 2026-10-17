package com.jsm.boardgame.holdem.qa

import com.jsm.boardgame.holdem.domain.model.Card
import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class NextDeckRequest(
    val holeCards: Map<Int, List<String>> = emptyMap(),
    val board: List<String> = emptyList(),
)

// 인가는 SecurityConfig 의 "/api/admin/**" → hasRole("ADMIN") 이 처리한다.
// 잘못된 입력은 400 — ResponseStatusException 은 GlobalExceptionHandler 가 errorCode=REQUEST_INVALID 로 만든다.
@RestController
@Profile("qa")
@RequestMapping("/api/admin/qa/holdem/tables")
class QaDeckController(private val shuffler: QaNewHandShuffler) {

    @PostMapping("/{tableId}/next-deck")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setNextDeck(@PathVariable tableId: Long, @RequestBody request: NextDeckRequest) {
        val spec = try {
            DeckSpec(
                holeCards = request.holeCards.mapValues { (_, cards) -> cards.map(Card::of) },
                board = request.board.map(Card::of),
            )
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        } catch (e: IllegalStateException) { // Card.of 의 잘못된 표기
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message, e)
        }
        shuffler.reserve(TableId(tableId), spec)
    }
}
