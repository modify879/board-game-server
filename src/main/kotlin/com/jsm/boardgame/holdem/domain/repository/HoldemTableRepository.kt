package com.jsm.boardgame.holdem.domain.repository

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId

interface HoldemTableRepository {
    fun findById(id: TableId): HoldemTable?

    /** 열려 있는(닫히지 않은) 테이블이 있는지. 좌석까지 읽을 필요가 없는 곳을 위해 구현이 더 싸게 덮어쓸 수 있다. */
    fun existsOpen(id: TableId): Boolean = findById(id) != null

    fun findByUserId(userId: UserId): HoldemTable?

    /**
     * 현재 어딘가에 앉아 있는 모든 사용자의 id. 부팅 시 미접속 좌석을 정리하는 스윕 대상을 찾는 용도.
     */
    fun findAllSeatedUserIds(): List<UserId>

    /**
     * 모든 테이블 id. 부팅 시 HandRecovery 가 다음 핸드 카운트다운을 새로 걸 대상을 훑는 용도.
     */
    fun findAllTableIds(): List<TableId>

    fun save(table: HoldemTable): HoldemTable
}
