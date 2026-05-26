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
import java.util.Date

// ======================== DTO ========================

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

@Serializable
data class GuestResponse(
    val id: Int,
    val fullName: String,
    val roomNumber: String,
    val phone: String,
    val isActive: Boolean
)

@Serializable
data class StaffResponse(
    val id: Int,
    val username: String,
    val role: String
)

@Serializable
data class CreateGuestRequest(
    val fullName: String,
    val roomNumber: String,
    val phone: String
)

@Serializable
data class UpdateGuestRequest(
    val fullName: String,
    val phone: String,
    val roomNumber: String,
    val isActive: Boolean
)

@Serializable
data class UpdateStaffRequest(
    val username: String,
    val role: String
)

// ======================== Роутинг ========================

fun Application.configureRouting() {
    val jsonWorker = Json { ignoreUnknownKeys = true }

    val jwtSecret = "super-secret-key-sochi-2026"
    val jwtIssuer = "http://0.0.0.0:8080/"
    val jwtAudience = "hotel-audience"

    routing {
        // Статические файлы и редирект
        staticResources("/", "static", index = "login.html")
        get("/") {
            call.respondRedirect("/login.html")
        }

        // Регистрация сотрудников (админ)
        post("/api/admin/staff") {
            try {
                val body = call.receiveText()
                val req = jsonWorker.decodeFromString<CreateStaffRequest>(body)

                val exists = transaction {
                    Staff.selectAll().where { Staff.username eq req.username }.firstOrNull()
                }

                if (exists != null) {
                    call.respond(HttpStatusCode.Conflict, "User already exists")
                    return@post
                }

                transaction {
                    Staff.insert {
                        it[Staff.username] = req.username
                        it[Staff.passwordHash] = req.password
                        it[Staff.role] = req.role
                    }
                }

                call.respond(HttpStatusCode.Created, mapOf("message" to "Staff created successfully"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
            }
        }

        // Авторизация (выдача JWT)
        post("/api/auth/login") {
            val req = call.receive<LoginRequest>()

            val authResponse = transaction {
                if (req.type == "GUEST") {
                    val guest = Guests.selectAll().where { Guests.phone eq req.identifier }.firstOrNull()
                    if (guest != null) {
                        val guestId = guest[Guests.id]
                        val token = JWT.create()
                            .withAudience(jwtAudience)
                            .withIssuer(jwtIssuer)
                            .withClaim("userId", guestId)
                            .withClaim("role", "GUEST")
                            .withExpiresAt(Date(System.currentTimeMillis() + 24 * 3600 * 1000))
                            .sign(Algorithm.HMAC256(jwtSecret))
                        AuthResponse(token = token, role = "GUEST", guestId = guestId)
                    } else null
                } else {
                    val staff = Staff.selectAll().where { Staff.username eq req.identifier }.firstOrNull()

                    if (staff != null && PasswordHasher.verify(req.password ?: "", staff[Staff.passwordHash])) {
                        val staffId = staff[Staff.id]
                        val staffRole = staff[Staff.role]
                        val token = JWT.create()
                            .withAudience(jwtAudience)
                            .withIssuer(jwtIssuer)
                            .withClaim("userId", staffId)
                            .withClaim("role", staffRole)
                            .withExpiresAt(Date(System.currentTimeMillis() + 24 * 3600 * 1000))
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

        // ==================== ЗАЩИЩЁННЫЕ РОУТЫ (JWT) ====================
        authenticate("auth-jwt") {

            // ---------- Гости (админ) ----------
            get("/api/admin/guests") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()
                if (role != "ADMIN") {
                    return@get call.respond(HttpStatusCode.Forbidden, "Only admin can view guests")
                }
                val guestsList = transaction {
                    Guests.selectAll().map { row ->
                        GuestResponse(
                            id = row[Guests.id],
                            fullName = row[Guests.fullName],
                            roomNumber = row[Guests.roomNumber],
                            phone = row[Guests.phone],
                            isActive = row[Guests.isActive]
                        )
                    }
                }
                call.respond(guestsList)
            }

            post("/api/admin/guests") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()
                if (role != "ADMIN") {
                    return@post call.respond(HttpStatusCode.Forbidden, "Only admin can add guests")
                }
                val req = call.receive<CreateGuestRequest>()
                transaction {
                    Guests.insert {
                        it[Guests.fullName] = req.fullName
                        it[Guests.roomNumber] = req.roomNumber
                        it[Guests.phone] = req.phone
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("message" to "Guest added successfully"))
            }

            put("/api/admin/guests/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()
                if (role != "ADMIN") {
                    return@put call.respond(HttpStatusCode.Forbidden, "Only admin can update guests")
                }
                val guestId = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid guest ID")
                val req = call.receive<UpdateGuestRequest>()
                val updated = transaction {
                    Guests.update({ Guests.id eq guestId }) {
                        it[Guests.fullName] = req.fullName
                        it[Guests.phone] = req.phone
                        it[Guests.roomNumber] = req.roomNumber
                        it[Guests.isActive] = req.isActive
                    }
                }
                if (updated > 0) {
                    call.respond(mapOf("message" to "Guest updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Guest not found")
                }
            }

            delete("/api/admin/guests/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()
                if (role != "ADMIN") {
                    return@delete call.respond(HttpStatusCode.Forbidden, "Only admin can delete guests")
                }
                val guestId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid guest ID")

                try {
                    transaction {
                        // 1. Удаляем все заявки этого гостя
                        Tickets.deleteWhere { Tickets.guestId eq guestId }
                        // 2. Удаляем самого гостя
                        val deleted = Guests.deleteWhere { Guests.id eq guestId }
                        if (deleted == 0) {
                            throw Exception("Guest not found")
                        }
                    }
                    call.respond(mapOf("message" to "Guest deleted successfully"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to delete guest: ${e.message}")
                }
            }

            // ---------- Сотрудники (админ) ----------
            post("/api/admin/staff") {
                try {
                    val body = call.receiveText()
                    val req = jsonWorker.decodeFromString<CreateStaffRequest>(body)

                    val exists = transaction {
                        Staff.selectAll().where { Staff.username eq req.username }.firstOrNull()
                    }

                    if (exists != null) {
                        call.respond(HttpStatusCode.Conflict, "User already exists")
                        return@post
                    }

                    // Хешируем пароль перед сохранением
                    val hashedPassword = PasswordHasher.hash(req.password)

                    transaction {
                        Staff.insert {
                            it[Staff.username] = req.username
                            it[Staff.passwordHash] = hashedPassword
                            it[Staff.role] = req.role
                        }
                    }

                    call.respond(HttpStatusCode.Created, mapOf("message" to "Staff created successfully"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
                }
            }

            put("/api/admin/staff/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()
                if (role != "ADMIN") {
                    return@put call.respond(HttpStatusCode.Forbidden, "Only admin can update staff")
                }
                val staffId = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid staff ID")
                val req = call.receive<UpdateStaffRequest>()
                val updated = transaction {
                    Staff.update({ Staff.id eq staffId }) {
                        it[Staff.username] = req.username
                        it[Staff.role] = req.role
                    }
                }
                if (updated > 0) {
                    call.respond(mapOf("message" to "Staff updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Staff not found")
                }
            }

            delete("/api/admin/staff/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val role = principal?.payload?.getClaim("role")?.asString()
                if (role != "ADMIN") {
                    return@delete call.respond(HttpStatusCode.Forbidden, "Only admin can delete staff")
                }
                val staffId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid staff ID")

                try {
                    transaction {
                        // Если есть заявки, назначенные на этого сотрудника – обнуляем assignedStaffId
                        Tickets.update({ Tickets.assignedStaffId eq staffId }) {
                            it[Tickets.assignedStaffId] = null
                        }
                        val deleted = Staff.deleteWhere { Staff.id eq staffId }
                        if (deleted == 0) {
                            throw Exception("Staff not found")
                        }
                    }
                    call.respond(mapOf("message" to "Staff deleted successfully"))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, "Failed to delete staff: ${e.message}")
                }
            }

            // ---------- Заявки (гость) ----------
            post("/api/tickets") {
                val principal = call.principal<JWTPrincipal>()
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

            get("/api/guest/tickets") {
                val principal = call.principal<JWTPrincipal>()
                val guestId = principal?.payload?.getClaim("userId")?.asInt()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, "Invalid token claims")
                val tickets = transaction {
                    (Tickets innerJoin Guests innerJoin Categories)
                        .selectAll().where { Tickets.guestId eq guestId }
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

            patch("/api/tickets/{id}") {
                val ticketId = call.parameters["id"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid ticket ID")
                val req = call.receive<CreateTicketRequest>()
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val updatedRows = transaction {
                    Tickets.update({ Tickets.id eq ticketId }) {
                        it[Tickets.categoryId] = req.categoryId
                        it[Tickets.description] = req.description
                        it[Tickets.updatedAt] = now
                    }
                }
                if (updatedRows > 0) {
                    call.respond(mapOf("message" to "Ticket updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ticket not found")
                }
            }

            // ---------- Заявки (админ/персонал) ----------
            listOf("/api/admin/tickets", "/api/staff/tickets").forEach { path ->
                get(path) {
                    val principal = call.principal<JWTPrincipal>()
                    val role = principal?.payload?.getClaim("role")?.asString()?.uppercase()
                    val allowedRoles = setOf("ADMIN", "CLEANER", "MASTER", "STAFF_CLEANER", "STAFF_ENGINEER", "STAFF_WAITER")
                    if (role == null || role !in allowedRoles) {
                        return@get call.respond(HttpStatusCode.Forbidden, "Only staff can view these tickets")
                    }
                    val statusFilter = call.request.queryParameters["status"]
                    val tickets = transaction {
                        val query = Tickets
                            .innerJoin(Guests, { Tickets.guestId }, { Guests.id })
                            .innerJoin(Categories, { Tickets.categoryId }, { Categories.id })
                        val select = if (statusFilter != null) {
                            query.selectAll().where { Tickets.status eq statusFilter }
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
            }

            put("/api/tickets/{id}/status") {
                val ticketId = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, "Invalid ticket ID")
                val req = call.receive<Map<String, String>>()
                val newStatus = req["status"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing status")
                val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val updatedRows = transaction {
                    Tickets.update({ Tickets.id eq ticketId }) {
                        it[Tickets.status] = newStatus
                        it[Tickets.updatedAt] = now
                    }
                }
                if (updatedRows > 0) {
                    call.respond(mapOf("message" to "Status updated successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ticket not found")
                }
            }

            delete("/api/tickets/{id}") {
                val ticketId = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, "Invalid ticket ID")
                val deletedRows = transaction {
                    Tickets.deleteWhere { Tickets.id eq ticketId }
                }
                if (deletedRows > 0) {
                    call.respond(mapOf("message" to "Ticket deleted successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, "Ticket not found")
                }
            }
        }
    }
}