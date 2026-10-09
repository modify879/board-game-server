package com.jsm.boardgame.holdem.application.event

import com.jsm.boardgame.holdem.domain.model.TableId
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.lang.reflect.ParameterizedType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TableClosedListenersTest {
    // 규칙: 테이블별 인메모리 상태는 TableId 를 키로 두고, TableClosed 를 커밋 후 리스너로 받아 스스로 치운다.
    // 컴포넌트(@Service·@Repository 등 포함)의 필드 중 키/원소가 TableId 인 Map·Collection 을 찾아 리스너를 강제한다.
    // Long 키로 둔 테이블별 상태는 이 검사에 보이지 않는다 — 그래서 TableId 키가 규칙이다.
    @Test
    fun `TableId 를 키로 가진 상태를 쥔 컴포넌트는 모두 TableClosed 를 커밋 후 리스너로 받는다`() {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Component::class.java))
        val components = scanner.findCandidateComponents("com.jsm.boardgame.holdem")
            .map { Class.forName(it.beanClassName) }

        val holders = components.filter { type -> type.declaredFields.any { holdsTableIdState(it.genericType) } }

        // 스캔이 조용히 아무것도 못 찾으면 검사가 무의미해진다.
        val names = holders.map { it.simpleName }.toSet()
        assertTrue(
            names.containsAll(setOf("TurnTimer", "SerialTableExecutor", "InMemoryJoinQueue", "TableViewSequence")),
            "TableId 키 상태를 못 찾았다: $names",
        )

        val offenders = holders.filterNot { type ->
            type.declaredMethods.any { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == TableClosed::class.java &&
                    method.getAnnotation(TransactionalEventListener::class.java)?.phase == TransactionPhase.AFTER_COMMIT
            }
        }
        assertEquals(emptyList(), offenders.map { it.simpleName }, "AFTER_COMMIT @TransactionalEventListener(TableClosed) 메서드가 없는 클래스")
    }

    private fun holdsTableIdState(type: java.lang.reflect.Type): Boolean {
        if (type !is ParameterizedType) return false
        val raw = type.rawType as? Class<*> ?: return false
        if (!Map::class.java.isAssignableFrom(raw) && !Collection::class.java.isAssignableFrom(raw)) return false
        return type.actualTypeArguments.firstOrNull() == TableId::class.java
    }
}
