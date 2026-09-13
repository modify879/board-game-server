package com.jsm.boardgame

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BoardGameApplication

fun main(args: Array<String>) {
    runApplication<BoardGameApplication>(*args)
}
