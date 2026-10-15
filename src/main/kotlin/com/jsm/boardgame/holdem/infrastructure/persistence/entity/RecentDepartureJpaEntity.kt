package com.jsm.boardgame.holdem.infrastructure.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** 최근에 떠난 사람이 가져간 금액 1행. PK 를 [userId] 로 둔 이유는 [HandInProgressJpaEntity] 와 같다. */
@Entity
@Table(name = "holdem_recent_departure")
class RecentDepartureJpaEntity(
    @Id
    @Column(name = "user_id")
    val userId: Long,
    @Column(name = "amount", nullable = false)
    var amount: Long,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
) : Persistable<Long> {
    @Transient
    private var new: Boolean = true

    override fun getId(): Long = userId
    override fun isNew(): Boolean = new

    @PostLoad
    @PostPersist
    fun markNotNew() {
        new = false
    }
}

interface RecentDepartureJpaRepository : JpaRepository<RecentDepartureJpaEntity, Long>
