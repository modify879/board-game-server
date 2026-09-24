package com.jsm.boardgame.holdem.domain.repository

import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId

interface HoldemTableRepository {
    fun findById(id: TableId): HoldemTable?

    fun findByUserId(userId: Long): HoldemTable?

    /** 대기 중인 참가 요청을 낸 사용자가 앉아 있(으려 하)는 테이블(없으면 null). `findByUserId` 의
     *  참가-요청 버전 — 요청은 사람당 전역에서 하나뿐이어야 한다. */
    fun findByPendingJoinUserId(userId: Long): HoldemTable?

    /**
     * 현재 어딘가에 앉아 있는 모든 사용자의 id. 부팅 시 미접속 좌석을 정리하는 스윕 대상을 찾는 용도.
     * 기본값으로 빈 목록을 주면 구현을 빠뜨린 걸 컴파일러가 안 잡아줘 스윕이 조용히 무력화되므로
     * 각 구현은 반드시 실제 값을 돌려주거나 명시적으로 emptyList() 를 선언해야 한다.
     */
    fun findAllSeatedUserIds(): List<Long>

    /**
     * `nextHandAt` 이 채워진(=자동 시작을 기다리는) 모든 테이블 id. 재시작 시 NextHandTimer 재무장
     * 스윕 대상. 기본값으로 빈 목록을 주지 않는다 — 구현을 빠뜨린 걸 컴파일러가 잡아야 스윕이
     * 조용히 무력화되지 않는다.
     */
    fun findAllPendingNextHandTableIds(): List<TableId>

    /**
     * 대기 중인 참가 요청이 있는 모든 테이블 id. 재시작 시 처리되지 않은 참가 요청을 다시 처리하는
     * 스윕 대상(HandRecovery). 기본값으로 빈 목록을 주지 않는다 — 구현을 빠뜨린 걸 컴파일러가 잡아야
     * 스윕이 조용히 무력화되지 않는다.
     */
    fun findAllTableIdsWithPendingJoinRequests(): List<TableId>

    fun save(table: HoldemTable): HoldemTable
}
