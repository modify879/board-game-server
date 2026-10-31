package com.jsm.boardgame.holdem.presentation.ws.payload

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 클라이언트가 의존하는 JSON 필드명과 중첩 구조를 고정한다. 앱 직렬화 설정은 기본값이라 컨텍스트 없이 같은 매퍼를 만든다. */
class SeatViewJsonContractTest {

    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    private fun JsonNode.fields(): Set<String> = propertyNames().toSet()

    @Test
    fun `SeatPrivateView 는 hand 아래에 홀카드, 현재 족보, 가능한 액션을 중첩해 직렬화한다`() {
        val view = SeatPrivateView(
            tableId = 7, seatNo = 2, leaveReservation = "SPECTATE", chipsReservation = 3_000,
            hand = PrivateHandView(
                holeCards = listOf("AS", "KD"),
                currentHand = CurrentHandView("PAIR", listOf(14, 9)),
                availableActions = AvailableActionsView(canCheck = false, callAmount = 200, minRaiseTo = 400, maxRaiseTo = 10_000),
            ),
            seq = 5,
        )

        val json = mapper.readTree(mapper.writeValueAsString(view))

        assertEquals(setOf("tableId", "seatNo", "leaveReservation", "chipsReservation", "hand", "seq"), json.fields())
        assertEquals("SPECTATE", json["leaveReservation"].asString())
        assertEquals(3_000L, json["chipsReservation"].asLong())
        val hand = json["hand"]
        assertEquals(setOf("holeCards", "currentHand", "availableActions"), hand.fields())
        assertEquals(listOf("AS", "KD"), hand["holeCards"].values().map { it.asString() })
        assertEquals(setOf("category", "tiebreakers"), hand["currentHand"].fields())
        assertEquals("PAIR", hand["currentHand"]["category"].asString())
        assertEquals(listOf(14, 9), hand["currentHand"]["tiebreakers"].values().map { it.asInt() })
        val actions = hand["availableActions"]
        assertEquals(setOf("canCheck", "callAmount", "minRaiseTo", "maxRaiseTo"), actions.fields())
        assertEquals(false, actions["canCheck"].asBoolean())
        assertEquals(200L, actions["callAmount"].asLong())
        assertEquals(400L, actions["minRaiseTo"].asLong())
        assertEquals(10_000L, actions["maxRaiseTo"].asLong())
    }

    @Test
    fun `핸드가 없는 SeatPrivateView 는 hand 와 예약 필드를 생략하지 않고 null 로 싣는다`() {
        val json = mapper.readTree(
            mapper.writeValueAsString(SeatPrivateView(7, 2, leaveReservation = null, chipsReservation = null, hand = null, seq = 1)),
        )

        assertEquals(setOf("tableId", "seatNo", "leaveReservation", "chipsReservation", "hand", "seq"), json.fields())
        assertTrue(json["hand"].isNull)
        assertTrue(json["leaveReservation"].isNull)
        assertTrue(json["chipsReservation"].isNull)
    }

    @Test
    fun `SeatPublicView 는 NOT_IN_HAND 상태와 awaitingBigBlind 를 싣는다`() {
        val view = SeatPublicView(
            seatNo = 3, userId = 9, stack = 10_000, totalContributed = 0, streetBet = 0,
            status = "NOT_IN_HAND", lastAction = null, presence = "SEATED", leaveReserved = false, awaitingBigBlind = true,
        )

        val json = mapper.readTree(mapper.writeValueAsString(view))

        assertEquals(
            setOf(
                "seatNo", "userId", "stack", "totalContributed", "streetBet", "status",
                "lastAction", "presence", "leaveReserved", "awaitingBigBlind",
            ),
            json.fields(),
        )
        assertEquals("NOT_IN_HAND", json["status"].asString())
        assertEquals(true, json["awaitingBigBlind"].asBoolean())
        assertTrue(json["lastAction"].isNull)
    }
}
