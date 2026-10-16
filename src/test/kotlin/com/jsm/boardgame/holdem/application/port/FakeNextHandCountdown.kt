package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId
import java.time.Duration

/** 서비스 테스트가 공유하는 대역. restart·cancel 호출만 기록하고 남은 시간은 늘 null 이다. */
class FakeNextHandCountdown : NextHandCountdown {
    val restarted = mutableListOf<TableId>()
    val cancelled = mutableListOf<TableId>()
    override fun restart(tableId: TableId) { restarted += tableId }
    override fun cancel(tableId: TableId) { cancelled += tableId }
    override fun remaining(tableId: TableId): Duration? = null
}
