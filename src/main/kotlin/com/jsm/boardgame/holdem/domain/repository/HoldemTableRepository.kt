package com.jsm.boardgame.holdem.domain.repository

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

interface HoldemTableRepository {
    fun findById(id: TableId): HoldemTable?

    fun findByUserId(userId: Long): HoldemTable?

    /**
     * 현재 어딘가에 앉아 있는 모든 사용자의 id. 부팅 시 미접속 좌석을 정리하는 스윕 대상을 찾는 용도.
     * 기본값으로 빈 목록을 주면 구현을 빠뜨린 걸 컴파일러가 안 잡아줘 스윕이 조용히 무력화되므로
     * 각 구현은 반드시 실제 값을 돌려주거나 명시적으로 emptyList() 를 선언해야 한다.
     */
    fun findAllSeatedUserIds(): List<Long>

    /**
     * 모든 테이블 id. 부팅 시 HandRecovery 가 다음 핸드 카운트다운을 새로 걸 대상을 훑는 용도.
     * 기본값으로 빈 목록을 주지 않는다 — 구현을 빠뜨린 걸 컴파일러가 잡아야 스윕이 조용히
     * 무력화되지 않는다.
     */
    fun findAllTableIds(): List<TableId>

    fun save(table: HoldemTable): HoldemTable
}
