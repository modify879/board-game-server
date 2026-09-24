package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.service.Shuffler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

private val identityShuffler = Shuffler { it }

private val objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

class HandStateJsonTest {

    @Test
    fun `레이즈 이후 스냅샷을 JSON 으로 직렬화·역직렬화해도 lastAggressorSeatNo 와 showdownLeaderSeatNo 가 보존된다`() {
        val hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000), 3 to Chips.of(10_000)),
            buttonSeatNo = 1,
            smallBlindSeatNo = 2,
            bigBlindSeatNo = 3,
            smallBlind = Chips.of(100),
            bigBlind = Chips.of(200),
            shuffler = identityShuffler,
        )
        hand.act(1, BettingAction.RaiseTo(Chips.of(600)))

        val json = hand.snapshot().toJson()
        val jsonText = objectMapper.writeValueAsString(json)
        val restored = objectMapper.readValue(jsonText, HandStateJson::class.java)

        assertEquals(json, restored)
        assertEquals(1, restored.showdownLeaderSeatNo)
        assertEquals(1, restored.currentRound!!.lastAggressorSeatNo)
    }

    @Test
    fun `lastAggressorSeatNo·showdownLeaderSeatNo 필드가 없는 예전 JSON 도 역직렬화된다`() {
        val oldJson = """
            {
              "buttonSeatNo": 1,
              "bigBlind": 200,
              "seatNos": [1, 2],
              "street": "PREFLOP",
              "board": [],
              "holeCards": {"1": ["2s", "3s"], "2": ["4s", "5s"]},
              "postflopFirstToActSeatNo": 2,
              "startingStacks": {"1": 10000, "2": 10000},
              "stacks": {"1": 9900, "2": 9800},
              "statuses": {"1": "ACTIVE", "2": "ACTIVE"},
              "totalContributed": {"1": 0, "2": 0},
              "currentRound": {
                "seats": [
                  {"seatNo": 1, "stack": 9900, "committed": 100, "status": "ACTIVE"},
                  {"seatNo": 2, "stack": 9800, "committed": 200, "status": "ACTIVE"}
                ],
                "currentBet": 200,
                "lastRaiseSize": 200,
                "lastFullLevel": 200,
                "actedSinceLastFullRaise": [],
                "toActSeatNo": 1
              }
            }
        """.trimIndent()

        val restored = objectMapper.readValue(oldJson, HandStateJson::class.java)

        assertNull(restored.showdownLeaderSeatNo)
        assertNull(restored.currentRound!!.lastAggressorSeatNo)
    }
}
