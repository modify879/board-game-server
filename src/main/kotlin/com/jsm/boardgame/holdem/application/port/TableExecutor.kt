package com.jsm.boardgame.holdem.application.port

import com.jsm.boardgame.holdem.domain.model.TableId

/**
 * 테이블마다 한 줄로 세워 실행한다 — 한 테이블의 상태를 바꾸는 명령은 전부 이것을 거친다.
 * 서버 한 대 전제; 여러 대로 가면 이 구현만 "테이블 주인 서버로 전달"로 바꾼다.
 */
interface TableExecutor {
    /** 그 테이블의 스레드에서 [task] 를 돌리고 끝날 때까지 기다린다. [task] 가 던진 예외는
     *  감싸지 않고 그대로 다시 던진다(BusinessException/errorCode 계약이 그대로 유지된다).
     *  이미 그 테이블의 스레드 위에 있으면(재진입) 그 자리에서 바로 실행한다 — 자기 자신을
     *  기다리는 데드락을 피한다. */
    fun <T> call(tableId: TableId, task: () -> T): T

    /** [task] 를 그 테이블의 스레드에 올려두고 기다리지 않는다(fire-and-forget). */
    fun post(tableId: TableId, task: () -> Unit)
}
