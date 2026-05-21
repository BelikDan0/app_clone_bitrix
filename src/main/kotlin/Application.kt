package com.example

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.Data.DatabaseConnector
import com.example.plugins.configureHttp
import com.example.plugins.configureRouting
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.EngineMain
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    val jwtSecret = "super-secret-key-sochi-2026" // Спрячь в конфиг потом
    val jwtIssuer = "http://0.0.0.0:8080/"
    val jwtAudience = "hotel-audience"

    install(Authentication) {
        jwt("auth-jwt") {
            realm = "Access to hotel API"
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) {
                    JWTPrincipal(credential.payload)
                } else null
            }
        }
    }
    // 1. Включаем JSON-сериализацию с настройками
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    // 2. Подключаем HTTP-плагины (CORS, Compression, Headers)
    configureHttp()

    // 3. Подключаем маршруты
    configureRouting()

    // 4. Инициализация БД
    DatabaseConnector.connect()
    DatabaseConnector.createTables()
}