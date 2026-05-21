package com.example.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.Data.AuthResponse
import com.example.Data.CreateStaffRequest
import com.example.Data.CreateTicketRequest
import com.example.Data.LoginRequest
import com.example.Data.Categories
import com.example.Data.Guests
import com.example.Data.Staff
import com.example.Data.Tickets
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Serializable
data class TicketResponse(
    val id: Int,
    val guestName: String,
    val roomNumber: String,
    val categoryName: String,
    val description: String,
    val status: String,
    val createdAt: String
)

fun Application.configureRouting() {
    // Конфигурация секретов для JWT (должна совпадать с той, что указана при install(Authentication))
    val jwtSecret = "super-secret-key-sochi-2026"
    val jwtIssuer = "http://0.0.0.0:8080/"
    val jwtAudience = "hotel-audience"

    routing {
        // Проверка работоспособности (открытые ресурсы)
        staticResources("/", "static", index = "login.html")
        get("/") {
            call.respondRedirect("/login.html")
        }

        // Регистрация сотрудников (открытый эндпоинт, либо можешь перенести внутрь authenticate для админов)
        post("/api/admin/staff") {
            try {
                val body = call.receiveText()
                val req = Json { ignoreUnknownKeys = true }.decodeFromString<CreateStaffRequest>(body)

                val exists = transaction {
                    Staff.select { Staff.username eq req.username }.firstOrNull()
                }

                if (exists != null) {
                    call.respond(HttpStatusCode.Conflict, "User already exists")
                    return@post
                }

                transaction {
                    Staff.insert {
                        it[username] = req.username
                        it[passwordHash] = req.password // В продакшене обязательно хешируй!
                        it[role] = req.role
                    }
                }

                call.respond(HttpStatusCode.Created, mapOf("message" to "Staff created successfully"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
            }
        }

        // Авторизация (выдача JWT токена)
        post("/api/auth/login") {
            val req = call.receive<LoginRequest>()

            val authResponse = transaction {
                if (req.type == "GUEST") {
                    val guest = Guests.select { Guests.phone eq req.identifier }.firstOrNull()
                    if (guest != null) {
                        val guestId = guest[Guests.id]
                        // Генерируем токен для Гостя
                        val token = JWT.create()
                            .withAudience(jwtAudience)
                            .withIssuer(jwtIssuer)
                            .withClaim("userId", guestId)
                            .withClaim("role", "GUEST")
                            .sign(Algorithm.HMAC256(jwtSecret))

                        AuthResponse(token = token, role = "GUEST", guestId = guestId)
                    } else null
                } else {
                    val staff = Staff.select {
                        (Staff.username eq req.identifier) and (Staff.passwordHash eq (req.password ?: ""))
                    }.firstOrNull()

                    if (staff != null) {
                        val staffId = staff[Staff.id]
                        val staffRole = staff[Staff.role]
                        // Генерируем токен для Персонала/Админа
                        val token = JWT.create()
                            .withAudience(jwtAudience)
                            .withIssuer(jwtIssuer)
                            .withClaim("userId", staffId)
                            .withClaim("role", staffRole)
                            .sign(Algorithm.HMAC256(jwtSecret))

                        AuthResponse(token = token, role = staffRole, guestId = null)
                    } else null
                }
            }

            if (authResponse != null) {
                call.respond(authResponse)
            } else {
                call.respond(HttpStatusCode.Unauthorized, "Invalid credentials")
            }
        }

        // ==========================================================
        // ЗАЩИЩЕННЫЕ РОУТЫ (Доступны только по валидному Bearer JWT токену)
        // ==========================================================
        authenticate("auth-jwt") {

            // Создание заявки гостем
            post("/api/tickets") {
                val principal = call.principal<JWTPrincipal>()
                // Вытаскиваем ID гостя прямо из токена, безопасность 100%
                val guestId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, "Invalid token claims")

                val req = call.receive<CreateTicketRequest>()
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                transaction {
                    Tickets.insert {
                        it[Tickets.guestId] = guestId
                        it[Tickets.categoryId] = req.categoryId
                        it[Tickets.description] = req.description
                        it[Tickets.status] = "NEW"
                        it[Tickets.createdAt] = now
                        it[Tickets.updatedAt] = now
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("message" to "Ticket created successfully"))
            }

            // Получение списка заявок текущего гостя
            get("/api/guest/tickets") {
                val principal = call.principal<JWTPrincipal>()
                val guestId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, "Invalid token claims")

                val tickets: List<TicketResponse> = transaction {
                    (Tickets innerJoin Guests innerJoin Categories)
                        .select { Tickets.guestId eq guestId }
                        .orderBy(Tickets.createdAt to SortOrder.DESC)
                        .map { row ->
                            TicketResponse(
                                id = row[Tickets.id],
                                guestName = row[Guests.fullName],
                                roomNumber = row[Guests.roomNumber],
                                categoryName = row[Categories.name],
                                description = row[Tickets.description],
                                status = row[Tickets.status],
                                createdAt = row[Tickets.createdAt]
                            )
                        }
                }
                call.respond(tickets)
            }

            // Получение всех заявок (для админа/персонала)
            get("/api/admin/tickets") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()

                if (role != "ADMIN" && role != "CLEANER" && role != "MASTER") {
                    return@get call.respond(HttpStatusCode.Forbidden, "Only staff can view all tickets")
                }

                val statusFilter = call.request.queryParameters["status"]

                val tickets: List<TicketResponse> = transaction {
                    val query = (Tickets innerJoin Guests innerJoin Categories)
                    val select = if (statusFilter != null) {
                        query.select { Tickets.status eq statusFilter }
                    } else {
                        query.selectAll()
                    }

                    select.orderBy(Tickets.createdAt to SortOrder.DESC)
                        .map { row ->
                            TicketResponse(
                                id = row[Tickets.id],
                                guestName = row[Guests.fullName],
                                roomNumber = row[Guests.roomNumber],
                                categoryName = row[Categories.name],
                                description = row[Tickets.description],
                                status = row[Tickets.status],
                                createdAt = row[Tickets.createdAt]
                            )
                        }
                }
                call.respond(tickets)
            }

            // Изменение статуса заявки сотрудником
            put("/api/tickets/{id}/status") {
                val ticketId = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ticket ID")

                val req = call.receive<Map<String, String>>()
                val newStatus = req["status"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing status")
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                val updatedRows = transaction {
                    Tickets.update({ Tickets.id eq ticketId }) {
                        it[status] = newStatus
                        it[updatedAt] = now
                    }
                }

                if (updatedRows > 0) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Status updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ticket not found")
                }
            }

            // Удаление заявки
            delete("/api/tickets/{id}") {
                val ticketId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ticket ID")

                val deletedRows = transaction {
                    Tickets.deleteWhere { Tickets.id eq ticketId }
                }

                if (deletedRows > 0) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Ticket deleted successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ticket not found")
                }
            }

            // Изменение содержимого заявки гостем (редактирование описания/категории)
            patch("/api/tickets/{id}") {
                val ticketId = call.parameters["id"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid ticket ID")

                val req = call.receive<CreateTicketRequest>()
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                val updatedRows = transaction {
                    Tickets.update({ Tickets.id eq ticketId }) {
                        it[categoryId] = req.categoryId
                        it[description] = req.description
                        it[updatedAt] = now
                    }
                }

                if (updatedRows > 0) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Ticket updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ticket not found")
                }
            }
        }
    }
}