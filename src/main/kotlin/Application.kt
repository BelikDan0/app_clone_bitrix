package com.example

import com.example.Data.DatabaseConnector
import com.example.plugins.configureHttp
import com.example.plugins.configureRouting
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    // Настройка JWT аутентификации
    val jwtSecret = "super-secret-key-sochi-2026"
    val jwtIssuer = "http://0.0.0.0:8080/"
    val jwtAudience = "hotel-audience"

    install(Authentication) {
        jwt("auth-jwt") {
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asInt()
                val role = credential.payload.getClaim("role").asString()
                if (userId != null && role != null) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }

    configureHttp()
    configureRouting()
    DatabaseConnector.connect()
    DatabaseConnector.createTables()
    DatabaseConnector.insertInitialData() // если нужно
}