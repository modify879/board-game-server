package com.jsm.boardgame.holdem

import com.jayway.jsonpath.JsonPath
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 공유 DB 에 열린 테이블이 100개를 넘어도 새 테이블을 찾도록 모든 페이지를 훑는다. */
internal fun MockMvc.allHoldemTableRows(accessToken: String): List<Map<String, Any>> {
    val rows = mutableListOf<Map<String, Any>>()
    var page = 0
    do {
        val body = perform(
            get("/api/holdem/tables?page=$page&size=100").header("Authorization", "Bearer $accessToken"),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        rows += JsonPath.read<List<Map<String, Any>>>(body, "$.content")
        val totalPages = JsonPath.read<Int>(body, "$.page.totalPages")
    } while (++page < totalPages)
    return rows
}
