package com.example.Data

import at.favre.lib.crypto.bcrypt.BCrypt
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object DatabaseConnector {
    fun connect() {
        Database.connect(
            url = "jdbc:postgresql://localhost:5432/database_number_hotel",
            driver = "org.postgresql.Driver",
            user = "postgres",
            password = "123"
        )
    }

    fun createTables() {
        transaction {
            SchemaUtils.create(Guests, Staff, Categories, Tickets)
        }
    }

    fun insertInitialData() {
        transaction {
            // Категории – не указываем id, пусть автоинкремент сам назначает
            if (Categories.selectAll().empty()) {
                Categories.insert { it[Categories.name] = "Уборка номера" }
                Categories.insert { it[Categories.name] = "Технический ремонт" }
                Categories.insert { it[Categories.name] = "Доставка еды / рум-сервис" }
                Categories.insert { it[Categories.name] = "Жалобы и вопросы" }
                println("✅ Категории добавлены")
            } else {
                println("ℹ️ Категории уже есть, пропускаем")
            }

            // Гости
            if (Guests.selectAll().empty()) {
                Guests.insert {
                    it[Guests.fullName] = "Иван Петров"
                    it[Guests.phone] = "+79161234567"
                    it[Guests.roomNumber] = "101"
                    it[Guests.isActive] = true
                }
                Guests.insert {
                    it[Guests.fullName] = "Мария Сидорова"
                    it[Guests.phone] = "+79162345678"
                    it[Guests.roomNumber] = "202"
                    it[Guests.isActive] = true
                }
                println("✅ Добавлены 2 гостя")
            } else {
                println("ℹ️ Гости уже есть, пропускаем")
            }

            // Администратор
            if (Staff.selectAll().empty()) {
                val hashedPassword = BCrypt.withDefaults().hashToString(12, "admin".toCharArray())
                Staff.insert {
                    it[Staff.username] = "admin"
                    it[Staff.passwordHash] = hashedPassword
                    it[Staff.role] = "ADMIN"
                }
                println("✅ Администратор создан (логин: admin, пароль: admin)")
            } else {
                println("ℹ️ Сотрудники уже есть, пропускаем")
            }
        }
    }
}

// Таблицы (без изменений)
object Guests : Table("guests") {
    val id = integer("id").autoIncrement()
    val fullName = varchar("full_name", 100)
    val phone = varchar("phone", 20)
    val roomNumber = varchar("room_number", 10)
    val isActive = bool("is_active").default(true)
    override val primaryKey = PrimaryKey(id, name = "PK_guests_id")
}

object Staff : Table("staff") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 50)
    val passwordHash = varchar("password_hash", 255)
    val role = varchar("role", 20)
    override val primaryKey = PrimaryKey(id, name = "PK_staff_id")
}

object Categories : Table("categories") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 50)
    override val primaryKey = PrimaryKey(id, name = "PK_categories_id")
}

object Tickets : Table("tickets") {
    val id = integer("id").autoIncrement()
    val guestId = integer("guest_id").references(Guests.id)
    val categoryId = integer("category_id").references(Categories.id)
    val description = text("description")
    val status = varchar("status", 20)
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)
    val assignedStaffId = integer("assigned_staff_id").nullable()
    override val primaryKey = PrimaryKey(id, name = "PK_tickets_id")
}