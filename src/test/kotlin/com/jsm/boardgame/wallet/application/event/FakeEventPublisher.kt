package com.jsm.boardgame.wallet.application.event

import org.springframework.context.ApplicationEventPublisher

/** 서비스 테스트가 공유하는 인메모리 대역. 발행한 이벤트를 쌓는다. */
class FakeEventPublisher : ApplicationEventPublisher {
    val events = mutableListOf<Any>()
    override fun publishEvent(event: Any) { events += event }
}
