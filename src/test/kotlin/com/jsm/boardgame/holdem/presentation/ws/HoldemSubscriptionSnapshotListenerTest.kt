package com.jsm.boardgame.holdem.presentation.ws

import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.QueuePosition
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.TurnCountdown
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.presentation.ws.payload.JoinQueueView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.simp.SimpMessageHeaderAccessor
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.simp.user.SimpSession
import org.springframework.messaging.simp.user.SimpUser
import org.springframework.messaging.simp.user.SimpUserRegistry
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver
import org.springframework.messaging.simp.user.UserDestinationMessageHandler
import org.springframework.messaging.support.ExecutorSubscribableChannel
import org.springframework.messaging.support.MessageBuilder
import java.lang.reflect.Proxy
import java.security.Principal

/** join-queue 스냅샷의 순서 보장 로직(STOMP 없이). 대기 중이면 테이블 실행기에 올리고, 실행기 안에서 다시 읽는다. */
class HoldemSubscriptionSnapshotListenerTest {

    private val user = UserId(7)
    private val table = TableId(3)

    private class PostRecordingExecutor : TableExecutor {
        val posted = mutableListOf<Pair<TableId, () -> Unit>>()
        override fun <T> call(tableId: TableId, task: () -> T): T = error("call 금지 — 인바운드 스레드를 막으면 안 된다")
        override fun post(tableId: TableId, task: () -> Unit) { posted += tableId to task }
    }

    private class FakeJoinQueue(var position: QueuePosition?) : JoinQueue {
        override fun enqueue(tableId: TableId, userId: UserId, postBlindImmediately: Boolean): Int = error("unused")
        override fun peekHead(tableId: TableId) = error("unused")
        override fun removeByUserId(userId: UserId): TableId? = error("unused")
        override fun entriesOf(tableId: TableId) = error("unused")
        override fun isQueued(userId: UserId): Boolean = error("unused")
        override fun tableOf(userId: UserId): TableId? = error("unused")
        override fun positionOf(userId: UserId): QueuePosition? = position
    }

    private class FakeQuery(var seat: SeatLocation?) : HoldemTableQueryRepository {
        override fun findSeatOf(userId: Long): SeatLocation? = seat
        override fun findAllTables(pageable: Pageable): Page<com.jsm.boardgame.holdem.application.query.view.TableSummaryView> = error("unused")
        override fun findEmptyTableIds(): List<Long> = error("unused")
    }

    private class Sent(val user: String, val dest: String, val payload: Any, val headers: MessageHeaders?)

    private val sent = mutableListOf<Sent>()
    private val template = object : SimpMessagingTemplate(MessageChannel { _, _ -> true }) {
        override fun convertAndSendToUser(user: String, destination: String, payload: Any, headers: MutableMap<String, Any>?) {
            sent += Sent(user, destination, payload, headers as? MessageHeaders)
        }
    }

    private val executor = PostRecordingExecutor()
    private val userDestinationHandler = UserDestinationMessageHandler(
        ExecutorSubscribableChannel(), ExecutorSubscribableChannel(), DefaultUserDestinationResolver(unused<SimpUserRegistry>()),
    )

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("unused") } as T

    /** 세션 "sess-1" 이 등록돼 있는지를 흉내 낸다. */
    private var sessionRegistered = true

    private val userRegistry: SimpUserRegistry = Proxy.newProxyInstance(
        SimpUserRegistry::class.java.classLoader, arrayOf(SimpUserRegistry::class.java),
    ) { _, m, args ->
        check(m.name == "getUser") { "unused" }
        Proxy.newProxyInstance(SimpUser::class.java.classLoader, arrayOf(SimpUser::class.java)) { _, um, uargs ->
            check(um.name == "getSession") { "unused" }
            if (sessionRegistered && uargs[0] == "sess-1") unused<SimpSession>() else null
        }
    } as SimpUserRegistry

    private fun subscribe(queue: FakeJoinQueue, query: FakeQuery) {
        val userName = user.value.toString()
        val listener = HoldemSubscriptionSnapshotListener(
            tables = unused<HoldemTableRepository>(),
            handStore = unused<HandStore>(),
            lastHandResults = unused<LastHandResultStore>(),
            messagingTemplate = template,
            sequence = TableViewSequence(),
            nextHandCountdown = unused<NextHandCountdown>(),
            turnCountdown = unused<TurnCountdown>(),
            joinQueue = queue,
            tableExecutor = executor,
            tableQuery = query,
            userRegistry = userRegistry,
        )
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE).apply {
            destination = HoldemDestinations.joinQueueDestination()
            sessionId = "sess-1"
            this.user = Principal { userName }
        }
        val message: Message<ByteArray> = MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders)
        listener.afterMessageHandled(message, MessageChannel { _, _ -> true }, userDestinationHandler, null)
    }

    @Test
    fun `대기 중이면 인라인으로 보내지 않고 그 테이블 실행기에 올린다`() {
        val queue = FakeJoinQueue(QueuePosition(table, 2))
        subscribe(queue, FakeQuery(null))

        assertThat(sent).isEmpty()
        assertThat(executor.posted.map { it.first }).containsExactly(table)

        executor.posted.single().second()

        val view = sent.single().payload as JoinQueueView.Position
        assertThat(view).isEqualTo(JoinQueueView.Position(3, 2))
        assertThat(SimpMessageHeaderAccessor.getSessionId(sent.single().headers!!)).isEqualTo("sess-1")
    }

    @Test
    fun `실행기 안에서 다시 읽었을 때 이미 입장해 대기열에 없으면 POSITION 이 아니라 SEATED 를 보낸다`() {
        val queue = FakeJoinQueue(QueuePosition(table, 1))
        val query = FakeQuery(null)
        subscribe(queue, query)

        // post 되고 실행되기 전에 입장 처리가 끝났다.
        queue.position = null
        query.seat = SeatLocation(tableId = 3, seatNo = 4, stack = 1000)
        executor.posted.single().second()

        assertThat(sent.single().payload).isEqualTo(JoinQueueView.Seated(3, 4))
    }

    @Test
    fun `대기 중이 아니고 착석 중이면 그 테이블 실행기에 올리고 실행하면 SEATED 를 보낸다`() {
        subscribe(FakeJoinQueue(null), FakeQuery(SeatLocation(3, 4, 1000)))

        assertThat(sent).isEmpty()
        assertThat(executor.posted.map { it.first }).containsExactly(table)

        executor.posted.single().second()

        assertThat(sent.single().payload).isEqualTo(JoinQueueView.Seated(3, 4))
    }

    @Test
    fun `대기도 착석도 아니면 아무것도 올리거나 보내지 않는다`() {
        subscribe(FakeJoinQueue(null), FakeQuery(null))

        assertThat(executor.posted).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `실행기 안에서 다시 읽었을 때 다른 테이블 대기열에 있으면 그 테이블로 다시 올린다`() {
        val queue = FakeJoinQueue(QueuePosition(table, 1))
        subscribe(queue, FakeQuery(null))

        queue.position = QueuePosition(TableId(9), 2)
        executor.posted.single().second()

        assertThat(sent).isEmpty()
        assertThat(executor.posted.map { it.first }).containsExactly(table, TableId(9))

        executor.posted.last().second()

        assertThat(sent.single().payload).isEqualTo(JoinQueueView.Position(9, 2))
    }

    @Test
    fun `구독한 세션이 더 이상 등록돼 있지 않으면 보내지 않는다`() {
        subscribe(FakeJoinQueue(QueuePosition(table, 1)), FakeQuery(null))

        sessionRegistered = false
        executor.posted.single().second()

        assertThat(sent).isEmpty()
    }
}
