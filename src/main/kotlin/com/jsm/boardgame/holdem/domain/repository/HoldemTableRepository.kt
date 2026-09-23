package com.jsm.boardgame.holdem.domain.repository

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

interface HoldemTableRepository {
    fun findById(id: TableId): HoldemTable?

    /** 주어진 사용자가 현재 앉아 있는 테이블(없으면 null). */
    fun findByUserId(userId: Long): HoldemTable?

    /**
     * 현재 어딘가에 앉아 있는 모든 사용자의 id. 부팅 시 미접속 좌석을 정리하는 스윕 대상을 찾는 용도.
     * 기본값으로 빈 목록을 주면 구현을 빠뜨린 걸 컴파일러가 안 잡아줘 스윕이 조용히 무력화되므로
     * 각 구현은 반드시 실제 값을 돌려주거나 명시적으로 emptyList() 를 선언해야 한다.
     */
    fun findAllSeatedUserIds(): List<Long>

    fun save(table: HoldemTable): HoldemTable
}
