package com.jsm.boardgame.common.persistence

import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConstraintViolationsTest {

    @Test
    fun `체인에 Hibernate 예외가 없으면 메시지 안의 제약 이름을 대소문자 무시로 찾는다`() {
        val e = DataIntegrityViolationException("ERROR: duplicate key value violates unique constraint \"UK_USERS_NICKNAME\"")

        assertEquals("uk_users_nickname", e.violatedConstraint("uk_users_username", "uk_users_nickname"))
    }

    @Test
    fun `체인에 Hibernate 예외가 없고 메시지에도 후보가 없으면 null 이다`() {
        val e = DataIntegrityViolationException("some other failure")

        assertNull(e.violatedConstraint("uk_users_nickname"))
    }

    @Test
    fun `Hibernate 예외가 제약 이름을 못 채우면 메시지로 되돌아간다`() {
        val cause = ConstraintViolationException("boom", SQLException("x"), null)
        val e = DataIntegrityViolationException("violates uk_users_nickname", cause)

        assertEquals("uk_users_nickname", e.violatedConstraint("uk_users_nickname"))
    }

    @Test
    fun `Hibernate 예외의 제약 이름이 있으면 메시지에 다른 후보가 있어도 그 이름만 쓴다`() {
        val cause = ConstraintViolationException("boom", SQLException("x"), "uk_users_username")
        val e = DataIntegrityViolationException("mentions uk_users_nickname", cause)

        assertNull(e.violatedConstraint("uk_users_nickname"))
        assertEquals("uk_users_username", e.violatedConstraint("uk_users_username"))
    }
}
