package com.jsm.boardgame

import java.util.concurrent.atomic.AtomicLong

/**
 * 테스트용 username/nickname 접미사. 한 실행(공유 Postgres) 안에서 충돌하지 않는다.
 * JVM 마다 다른 3자 태그 + 프로세스 전역 카운터(base36, 6자 고정) = 9자.
 * `"u" + suffix()` 는 Username(4~20자 [a-z0-9_]), `"n" + suffix()` 는 Nickname(2~12자) 규칙을 만족한다.
 */
object UniqueNames {
    private val jvmTag = (0 until 3).map { "0123456789abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")
    private val counter = AtomicLong()

    fun suffix(): String = jvmTag + counter.incrementAndGet().toString(36).padStart(6, '0')
}
