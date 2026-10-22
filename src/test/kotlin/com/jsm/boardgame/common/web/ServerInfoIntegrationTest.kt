package com.jsm.boardgame.common.web

import com.jsm.boardgame.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ServerInfoIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `기본 프로필에서는 토큰 없이 default 를 돌려준다`() {
        mockMvc.perform(get("/api/server-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.environment").value("default"))
    }
}
