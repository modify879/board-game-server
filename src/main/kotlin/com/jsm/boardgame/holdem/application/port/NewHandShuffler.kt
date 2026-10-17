package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.service.Shuffler

/** 새 핸드의 덱을 섞을 셔플러를 고른다. 운영 구현은 항상 같은 무작위 셔플러를 돌려준다(QA 빌드만 다른 구현을 넣는다). */
fun interface NewHandShuffler {
    fun shufflerFor(tableId: TableId, seatNos: List<Int>, buttonSeatNo: Int): Shuffler
}
