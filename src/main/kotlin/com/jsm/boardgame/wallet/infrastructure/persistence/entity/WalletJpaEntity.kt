package com.jsm.boardgame.wallet.infrastructure.persistence.entity

import com.jsm.boardgame.wallet.domain.model.Money
import com.jsm.boardgame.wallet.domain.model.Wallet
import com.jsm.boardgame.wallet.domain.model.WalletId
import com.linecorp.kotlinjdsl.support.spring.data.jpa.repository.KotlinJdslJpqlExecutor
import jakarta.persistence.CheckConstraint
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional

@Entity
@Table(
    name = "wallets",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_wallets_user", columnNames = ["user_id"]),
    ],
    check = [
        CheckConstraint(name = "ck_wallets_balance_non_negative", constraint = "balance >= 0"),
    ],
)
class WalletJpaEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "balance", nullable = false)
    var balance: Long,
)

interface WalletJpaRepository :
    JpaRepository<WalletJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    /**
     * 명령 경로 전용 조회다. 행이 있으면 트랜잭션이 끝날 때까지 잠근다(`SELECT ... FOR UPDATE`).
     * 잠금 순서는 요청 행 → 지갑 행이다. 지갑을 잠근 뒤에 요청 행이나 홀덤 테이블을 잠그지 않는다 —
     * 어기면 데드락이다(CLAUDE.md 참고). 조회 경로는 이 메서드를 쓰지 않는다
     * (`WalletQueryRepositoryAdapter` 가 JDSL 로 따로 읽는다).
     *
     * `@Transactional` 을 명시하는 이유: Spring Data 리포지토리는 새로 선언한 쿼리 메서드에
     * 기본 `readOnly = true` 트랜잭션을 씌운다. Postgres 는 읽기 전용 트랜잭션에서
     * `SELECT ... FOR UPDATE` 를 거부하므로, 이 메서드만 명시적으로 쓰기 가능 트랜잭션을 연다.
     * 이미 트랜잭션 안에서 부르면(모든 명령 서비스가 그렇다) 그 트랜잭션에 그대로 합류한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional
    @Query("select w from WalletJpaEntity w where w.userId = :userId")
    fun findByUserIdForUpdate(@Param("userId") userId: Long): WalletJpaEntity?
}

fun WalletJpaEntity.toDomain(): Wallet =
    Wallet.reconstitute(
        id = WalletId(id),
        userId = userId,
        balance = Money.reconstitute(balance),
    )

fun Wallet.toJpaEntity(): WalletJpaEntity =
    WalletJpaEntity(
        id = id?.value ?: 0,
        userId = userId,
        balance = balance.amount,
    )
