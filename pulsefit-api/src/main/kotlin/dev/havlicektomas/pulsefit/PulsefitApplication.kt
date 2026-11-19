package dev.havlicektomas.pulsefit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PulsefitApplication

fun main(args: Array<String>) {
    runApplication<PulsefitApplication>(*args)
}
