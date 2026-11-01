package com.jsm.boardgame.holdem.presentation.ws.subscription

import com.jsm.boardgame.holdem.presentation.ws.HoldemDestinations
import com.jsm.boardgame.holdem.presentation.ws.broadcast.TableViewSequence
import com.jsm.boardgame.holdem.domain.repository.HandStore
import com.jsm.boardgame.holdem.domain.model.TestTablePolicy
import com.jsm.boardgame.holdem.application.port.JoinQueue
import com.jsm.boardgame.holdem.application.port.LastHandResultStore
import com.jsm.boardgame.holdem.application.port.NextHandCountdown
import com.jsm.boardgame.holdem.application.port.QueuePosition
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.application.port.TurnCountdown
import com.jsm.boardgame.holdem.application.query.port.HoldemTableQueryRepository
import com.jsm.boardgame.holdem.application.query.view.SeatLocation
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.model.TableId
import com.jsm.boardgame.holdem.domain.model.UserId
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.repository.FakeHandStore
import com.jsm.boardgame.holdem.domain.repository.FakeHoldemTableRepository
import com.jsm.boardgame.holdem.application.port.FakeLastHandResultStore
import com.jsm.boardgame.holdem.application.port.FakeNextHandCountdown
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.holdem.presentation.ws.payload.SeatPrivateView
import com.jsm.boardgame.holdem.presentation.ws.payload.TableClosedView
import org.springframework.messaging.MessageHandler
import kotlin.test.assertFailsWith
import com.jsm.boardgame.holdem.presentation.ws.payload.JoinQueueView
import com.jsm.boardgame.holdem.presentation.ws.payload.TablePublicView
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
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler
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
        override fun enqueue(tableId: TableId, userId: UserId, postBlindImmediately: Boolean, buyIn: Chips): Int = error("unused")
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
        override fun findPolicyOf(tableId: Long): com.jsm.boardgame.holdem.application.query.view.TablePolicyView? = error("unused")
        override fun existsHandInProgress(tableId: Long): Boolean = error("unused")
        override fun findTableIdsWithHandInProgress(tableIds: Collection<Long>): Set<Long> = throw UnsupportedOperationException()
    }

    private class Sent(val user: String, val dest: String, val payload: Any, val headers: MessageHeaders?)

    private val sent = mutableListOf<Sent>()
    private val publicSent = mutableListOf<Any>()
    private val template = object : SimpMessagingTemplate(MessageChannel { _, _ -> true }) {
        override fun convertAndSend(destination: String, payload: Any) {
            publicSent += payload
        }

        override fun convertAndSendToUser(user: String, destination: String, payload: Any, headers: MutableMap<String, Any>?) {
            sent += Sent(user, destination, payload, headers as? MessageHeaders)
        }

        override fun convertAndSendToUser(user: String, destination: String, payload: Any) {
            sent += Sent(user, destination, payload, null)
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

    private var subscribeSessionId: String? = "sess-1"

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
            sessionId = subscribeSessionId
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
        assertThat(executor.posted).hasSize(1)
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

    @Test
    fun `실행기 안에서 다시 읽었을 때 다른 테이블에 착석해 있으면 그 테이블로 다시 올리고 실행하면 SEATED 를 보낸다`() {
        val queue = FakeJoinQueue(QueuePosition(table, 1))
        val query = FakeQuery(null)
        subscribe(queue, query)

        queue.position = null
        query.seat = SeatLocation(tableId = 9, seatNo = 2, stack = 1000)
        executor.posted.single().second()

        assertThat(sent).isEmpty()
        assertThat(executor.posted.map { it.first }).containsExactly(table, TableId(9))

        executor.posted.last().second()

        assertThat(sent.single().payload).isEqualTo(JoinQueueView.Seated(9, 2))
    }

    @Test
    fun `두 번째 올린 작업 안에서도 또 옮겨갔으면 더 올리지 않고 아무것도 보내지 않는다`() {
        val queue = FakeJoinQueue(QueuePosition(table, 1))
        subscribe(queue, FakeQuery(null))

        queue.position = QueuePosition(TableId(9), 1)
        executor.posted.single().second()
        queue.position = QueuePosition(TableId(11), 1)
        executor.posted.last().second()

        assertThat(sent).isEmpty()
        assertThat(executor.posted.map { it.first }).containsExactly(table, TableId(9))
    }

    @Test
    fun `sessionId 가 없으면 보내지 않는다`() {
        subscribeSessionId = null
        subscribe(FakeJoinQueue(QueuePosition(table, 1)), FakeQuery(null))

        executor.posted.single().second()

        assertThat(sent).isEmpty()
    }

    @Test
    fun `테이블 토픽 스냅샷은 인바운드 스레드에서 읽지 않고 실행기에서 seq 와 상태를 함께 읽는다`() {
        val sequence = TableViewSequence()
        sequence.next(table)
        val stored = HoldemTable.create("t", TestTablePolicy).apply { sitDown(user, Chips.of(10_000)) }
        var current: HoldemTable? = stored
        val tables = Proxy.newProxyInstance(
            HoldemTableRepository::class.java.classLoader, arrayOf(HoldemTableRepository::class.java),
        ) { _, m, _ -> check(m.name.startsWith("findById")) { "unused" }; current } as HoldemTableRepository
        val listener = HoldemSubscriptionSnapshotListener(
            tables = tables,
            handStore = nullStub<HandStore>(),
            lastHandResults = nullStub<LastHandResultStore>(),
            messagingTemplate = template,
            sequence = sequence,
            nextHandCountdown = nullStub<NextHandCountdown>(),
            turnCountdown = nullStub<TurnCountdown>(),
            joinQueue = unused<JoinQueue>(),
            tableExecutor = executor,
            tableQuery = unused<HoldemTableQueryRepository>(),
            userRegistry = userRegistry,
        )
        val userName = user.value.toString()
        val accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE).apply {
            destination = HoldemDestinations.publicTopicOf(table.value)
            sessionId = "sess-1"
            this.user = Principal { userName }
        }
        val broker = SimpleBrokerMessageHandler(
            ExecutorSubscribableChannel(), ExecutorSubscribableChannel(), ExecutorSubscribableChannel(), listOf("/topic"),
        )
        listener.afterMessageHandled(MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders), MessageChannel { _, _ -> true }, broker, null)

        // 대기 중에 일어난 일어서기: seq 는 오르고 상태는 바뀐다. 스냅샷은 그 뒤의 둘을 함께 읽어야 한다.
        assertThat(publicSent).isEmpty()
        sequence.next(table)
        stored.standUp(user)
        executor.posted.single().second()

        val view = publicSent.single() as TablePublicView
        assertThat(view.seq).isEqualTo(2)
        assertThat(view.seats).isEmpty()
    }

    private inline fun <reified T : Any> nullStub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private val brokerHandler = SimpleBrokerMessageHandler(
        ExecutorSubscribableChannel(), ExecutorSubscribableChannel(), ExecutorSubscribableChannel(), listOf("/topic"),
    )

    private class SnapshotFixture {
        val tables = FakeHoldemTableRepository()
        val handStore = FakeHandStore()
        var tableId: TableId = TableId(0)
        lateinit var hand: Hand
    }

    /** 사용자 7(좌석 1)과 8(좌석 2)이 앉아 있고 핸드가 진행 중인 테이블. */
    private fun fixtureWithHand(): SnapshotFixture {
        val f = SnapshotFixture()
        val t = HoldemTable.create("t", TestTablePolicy).apply {
            sitDown(user, Chips.of(10_000))
            sitDown(UserId(8), Chips.of(10_000))
        }
        val saved = f.tables.save(t)
        f.tableId = saved.id!!
        f.hand = Hand.start(
            mapOf(1 to Chips.of(10_000), 2 to Chips.of(10_000)), 1, 1, 2, saved.smallBlind, saved.bigBlind, Shuffler { it },
        )
        f.handStore.save(f.tableId, f.hand)
        return f
    }

    private fun snapshotListener(
        tables: HoldemTableRepository,
        handStore: HandStore = FakeHandStore(),
        messagingTemplate: SimpMessagingTemplate = template,
        registry: SimpUserRegistry = userRegistry,
    ) = HoldemSubscriptionSnapshotListener(
        tables = tables,
        handStore = handStore,
        lastHandResults = FakeLastHandResultStore(),
        messagingTemplate = messagingTemplate,
        sequence = TableViewSequence(),
        nextHandCountdown = FakeNextHandCountdown(),
        turnCountdown = nullStub<TurnCountdown>(),
        joinQueue = unused<JoinQueue>(),
        tableExecutor = executor,
        tableQuery = unused<HoldemTableQueryRepository>(),
        userRegistry = registry,
    )

    private fun deliver(
        listener: HoldemSubscriptionSnapshotListener,
        destination: String?,
        handler: MessageHandler,
        command: StompCommand = StompCommand.SUBSCRIBE,
        principalName: String? = user.value.toString(),
        ex: Exception? = null,
    ) {
        val accessor = StompHeaderAccessor.create(command).apply {
            destination?.let { this.destination = it }
            sessionId = "sess-1"
            principalName?.let { name -> this.user = Principal { name } }
        }
        listener.afterMessageHandled(MessageBuilder.createMessage(ByteArray(0), accessor.messageHeaders), MessageChannel { _, _ -> true }, handler, ex)
    }

    @Test
    fun `처리 중 예외가 났거나 구독이 아니거나 목적지·사용자를 알 수 없으면 스냅샷을 올리지 않는다`() {
        val f = fixtureWithHand()
        val listener = snapshotListener(f.tables, f.handStore)
        val topic = HoldemDestinations.publicTopicOf(f.tableId.value)

        deliver(listener, topic, brokerHandler, ex = IllegalStateException("handler failed"))
        deliver(listener, topic, brokerHandler, command = StompCommand.SEND)
        deliver(listener, null, brokerHandler)
        deliver(listener, topic, brokerHandler, principalName = null)
        deliver(listener, topic, brokerHandler, principalName = "not-a-number")
        deliver(listener, "/topic/lobby", brokerHandler)

        assertThat(executor.posted).isEmpty()
        assertThat(publicSent).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `구독을 실제로 등록한 핸들러가 아니면 개인 큐든 공개 토픽이든 join-queue 든 스냅샷을 올리지 않는다`() {
        val f = fixtureWithHand()
        val listener = snapshotListener(f.tables, f.handStore)
        val annotationHandler = MessageHandler { }

        deliver(listener, HoldemDestinations.privateQueueOf(f.tableId.value), brokerHandler) // 개인 큐는 UserDestinationMessageHandler 만
        deliver(listener, HoldemDestinations.privateQueueOf(f.tableId.value), annotationHandler)
        deliver(listener, HoldemDestinations.publicTopicOf(f.tableId.value), userDestinationHandler) // 공개 토픽은 SimpleBrokerMessageHandler 만
        deliver(listener, HoldemDestinations.publicTopicOf(f.tableId.value), annotationHandler)
        deliver(listener, HoldemDestinations.joinQueueDestination(), brokerHandler)

        assertThat(executor.posted).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `닫혔거나 없는 테이블의 공개 토픽 구독에는 닫힘 알림을 다시 보내고 개인 큐 구독에는 아무것도 보내지 않는다`() {
        val listener = snapshotListener(FakeHoldemTableRepository())

        deliver(listener, HoldemDestinations.publicTopicOf(99), brokerHandler)
        executor.posted.single().second()
        assertThat(publicSent.single()).isEqualTo(TableClosedView(99))

        publicSent.clear()
        executor.posted.clear()
        deliver(listener, HoldemDestinations.privateQueueOf(99), userDestinationHandler)
        executor.posted.single().second()

        assertThat(publicSent).isEmpty()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `착석자의 개인 큐 구독은 공개 뷰와 본인 개인 뷰만 보내고 공개 쪽에는 개인 뷰가 섞이지 않으며 상대 카드는 어디에도 없다`() {
        val f = fixtureWithHand()
        val listener = snapshotListener(f.tables, f.handStore)

        deliver(listener, HoldemDestinations.privateQueueOf(f.tableId.value), userDestinationHandler)
        executor.posted.single().second()

        assertThat(publicSent.single()).isInstanceOf(TablePublicView::class.java)
        val privateSend = sent.single()
        assertThat(privateSend.user).isEqualTo("7")
        assertThat(privateSend.dest).isEqualTo(HoldemDestinations.privateQueueSendTargetOf(f.tableId.value))
        val view = privateSend.payload as SeatPrivateView
        assertThat(view.seatNo).isEqualTo(1)
        val own = f.hand.holeCardsOf(1).map { it.toString() }
        assertThat(view.hand!!.holeCards).isEqualTo(own)
        val opponent = f.hand.holeCardsOf(2).map { it.toString() }
        assertThat(view.hand.holeCards).doesNotContainAnyElementsOf(opponent)
    }

    @Test
    fun `좌석이 없는 사용자의 개인 큐 구독은 공개 뷰만 받고 개인 뷰는 전혀 나가지 않는다`() {
        val f = fixtureWithHand()
        val listener = snapshotListener(f.tables, f.handStore)

        deliver(listener, HoldemDestinations.privateQueueOf(f.tableId.value), userDestinationHandler, principalName = "99")
        executor.posted.single().second()

        assertThat(publicSent.single()).isInstanceOf(TablePublicView::class.java)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `활성 세션이 레지스트리에 없어도 개인 뷰는 그 사용자 앞으로만 나가고 공개 토픽에는 흘러가지 않는다`() {
        val f = fixtureWithHand()
        val listener = snapshotListener(f.tables, f.handStore, registry = nullStub<SimpUserRegistry>())

        deliver(listener, HoldemDestinations.privateQueueOf(f.tableId.value), userDestinationHandler)
        executor.posted.single().second()

        assertThat(publicSent).allMatch { it is TablePublicView }
        assertThat(sent.map { it.user }).containsExactly("7")
        assertThat(sent.single().payload).isInstanceOf(SeatPrivateView::class.java)
    }

    @Test
    fun `개인 뷰 전송이 실패해도 공개 토픽으로 개인 뷰를 대신 보내지 않는다`() {
        val f = fixtureWithHand()
        val failingPublic = mutableListOf<Any>()
        val failingTemplate = object : SimpMessagingTemplate(MessageChannel { _, _ -> true }) {
            override fun convertAndSend(destination: String, payload: Any) { failingPublic += payload }
            override fun convertAndSendToUser(user: String, destination: String, payload: Any) =
                throw IllegalStateException("broker down")
        }
        val listener = snapshotListener(f.tables, f.handStore, messagingTemplate = failingTemplate)

        deliver(listener, HoldemDestinations.privateQueueOf(f.tableId.value), userDestinationHandler)

        assertFailsWith<IllegalStateException> { executor.posted.single().second() }
        assertThat(failingPublic).allMatch { it is TablePublicView }
    }

    @Test
    fun `join-queue 스냅샷은 실행기 안에서 대기도 착석도 아니게 됐으면 아무것도 보내지 않는다`() {
        val queue = FakeJoinQueue(QueuePosition(table, 1))
        subscribe(queue, FakeQuery(null))

        queue.position = null
        executor.posted.single().second()

        assertThat(sent).isEmpty()
        assertThat(executor.posted).hasSize(1)
    }
}
