package com.jsm.boardgame.holdem.infrastructure.executor

import com.jsm.boardgame.TestcontainersConfiguration
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnCommand
import com.jsm.boardgame.holdem.application.command.usecase.ExpireTurnUseCase
import com.jsm.boardgame.holdem.application.command.usecase.PlayActionCommand
import com.jsm.boardgame.holdem.application.command.usecase.PlayActionUseCase
import com.jsm.boardgame.holdem.application.port.HandStore
import com.jsm.boardgame.holdem.application.port.TableExecutor
import com.jsm.boardgame.holdem.domain.model.BettingAction
import com.jsm.boardgame.holdem.domain.model.Chips
import com.jsm.boardgame.holdem.domain.model.Hand
import com.jsm.boardgame.holdem.domain.model.HoldemTable
import com.jsm.boardgame.holdem.domain.repository.HoldemTableRepository
import com.jsm.boardgame.holdem.domain.service.Shuffler
import com.jsm.boardgame.user.domain.model.Nickname
import com.jsm.boardgame.user.domain.model.PasswordHash
import com.jsm.boardgame.user.domain.model.User
import com.jsm.boardgame.user.domain.model.Username
import com.jsm.boardgame.user.domain.repository.UserRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [TableExecutor] 의 실제 구현([SerialTableExecutor])이 같은 테이블에 대한 명령을 정말로 한 스레드
 * 위에서 직렬화하는지 증명한다 — 페이크가 아니라 스프링이 실제로 띄운 빈을 그대로 쓴다.
 *
 * 경합은 프리플랍 SB(버튼) 콜 다음, BB 의 옵션 차례에서 벌인다 — 그래야 시간 만료가 폴드(즉시
 * 기립)가 아니라 체크로 처리되어, 착석 인원이 줄어드는 부수효과 없이 "직렬화 자체" 만 검증할 수
 * 있다. 헤즈업은 포스트플랍 첫 액션도 BB 라(Hand.start 의 postflopFirstToActSeatNo), 둘 중 어느
 * 쪽이 먼저 실행되어 라운드를 넘기더라도 남은 한 쪽은 "그 다음 스트리트의 진짜 자기 차례"를 만나
 * 정상적으로 끝나거나, 아직 자기 차례가 아니라서 [ExpireTurnService] 의 stale 가드에 조용히
 * 걸린다 — 어느 순서로 실행되든 예외가 나지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TableActorRaceIntegrationTest {

    @Autowired
    private lateinit var tables: HoldemTableRepository

    @Autowired
    private lateinit var handStore: HandStore

    @Autowired
    private lateinit var tableExecutor: TableExecutor

    @Autowired
    private lateinit var expireTurnUseCase: ExpireTurnUseCase

    @Autowired
    private lateinit var playActionUseCase: PlayActionUseCase

    @Autowired
    private lateinit var shuffler: Shuffler

    @Autowired
    private lateinit var users: UserRepository

    // fk_holdem_seats_user 때문에 합성 userId 로는 착석 행을 저장할 수 없다 — 실제 사용자 행을 만든다.
    private fun uniqueUserId(): Long {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(9).lowercase()
        val user = User.register(
            username = Username.of("u$suffix"),
            passwordHash = PasswordHash("hashed-password-value"),
            nickname = Nickname.of("n" + suffix.take(5)),
        )
        return users.save(user).id!!.value
    }

    @Test
    fun `차례 만료와 플레이어 액션이 같은 테이블에서 동시에 들어와도 충돌 없이 하나만 반영된다`() {
        var table = HoldemTable.create("race-${System.nanoTime()}")
        table.sitDown(uniqueUserId(), Chips.of(10_000))
        table.sitDown(uniqueUserId(), Chips.of(10_000))
        table.moveButtonToNextOccupiedSeat()
        val tableId = tables.save(table).id!!
        table = tables.findById(tableId)!!

        val buttonSeatNo = table.buttonSeatNo!!
        val bigBlindSeatNo = table.occupiedSeats().map { it.seatNo }.single { it != buttonSeatNo }
        val stacks = mapOf(buttonSeatNo to Chips.of(10_000), bigBlindSeatNo to Chips.of(10_000))
        val hand = Hand.start(
            stacks = stacks,
            buttonSeatNo = buttonSeatNo,
            smallBlindSeatNo = buttonSeatNo,
            bigBlindSeatNo = bigBlindSeatNo,
            smallBlind = table.smallBlind,
            bigBlind = table.bigBlind,
            shuffler = shuffler,
        )
        // 테스트 준비 단계(경합의 일부가 아니다) - SB(버튼)가 콜해 BB 에게 체크 옵션을 넘긴다.
        hand.act(buttonSeatNo, BettingAction.Call)
        handStore.save(tableId, hand)

        val actingSeatNo = hand.toActSeatNo!!
        assertEquals(bigBlindSeatNo, actingSeatNo)
        val actingUserId = table.seatAt(actingSeatNo)!!.userId

        val ready = CountDownLatch(2)
        val results = arrayOfNulls<Throwable>(2)

        val expireThread = Thread {
            ready.countDown()
            ready.await()
            try {
                tableExecutor.call(tableId) {
                    expireTurnUseCase.expire(ExpireTurnCommand(tableId.value, actingSeatNo))
                }
            } catch (t: Throwable) {
                results[0] = t
            }
        }
        val playThread = Thread {
            ready.countDown()
            ready.await()
            try {
                tableExecutor.call(tableId) {
                    playActionUseCase.play(PlayActionCommand(tableId.value, actingUserId, "CALL", null))
                }
            } catch (t: Throwable) {
                results[1] = t
            }
        }

        expireThread.start()
        playThread.start()
        expireThread.join(5_000)
        playThread.join(5_000)

        assertNull(results[0])
        assertNull(results[1])
        assertNotNull(handStore.find(tableId))
        assertEquals(2, tables.findById(tableId)!!.occupiedSeats().size)
    }
}
