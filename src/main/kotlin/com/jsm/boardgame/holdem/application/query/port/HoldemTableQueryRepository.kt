package com.jsm.boardgame.holdem.application.query.port

import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.application.query.view.TablePolicyView
import com.jsm.boardgame.holdem.application.query.view.TableSummaryView
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface HoldemTableQueryRepository {
    fun findSeatOf(userId: Long): SeatLocation?

    /** 열린 테이블의 정책 컬럼. 없거나 닫혔으면 null. */
    fun findPolicyOf(tableId: Long): TablePolicyView?

    /** 진행 중인 핸드 스냅샷 행이 있는가. 끝난 핸드는 정산과 같은 트랜잭션에서 지워지므로 행이 있으면 진행 중이다. */
    fun existsHandInProgress(tableId: Long): Boolean

    fun findAllTables(pageable: Pageable): Page<TableSummaryView>

    /** 열려 있고 앉은 사용자가 없는 테이블 id. 방치된 테이블을 닫을 후보를 찾는 용도. */
    fun findEmptyTableIds(): List<Long>
}
