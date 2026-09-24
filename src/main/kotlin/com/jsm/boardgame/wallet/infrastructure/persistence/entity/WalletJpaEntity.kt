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
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository

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

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0,
)

interface WalletJpaRepository :
    JpaRepository<WalletJpaEntity, Long>,
    KotlinJdslJpqlExecutor {

    fun findByUserId(userId: Long): WalletJpaEntity?
}

fun WalletJpaEntity.toDomain(): Wallet =
    Wallet.reconstitute(
        id = WalletId(id),
        userId = userId,
        balance = Money.reconstitute(balance),
        version = version,
    )

fun Wallet.toJpaEntity(): WalletJpaEntity =
    WalletJpaEntity(
        id = id?.value ?: 0,
        userId = userId,
        balance = balance.amount,
        version = version,
    )
