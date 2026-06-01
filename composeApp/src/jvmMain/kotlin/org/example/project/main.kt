package org.example.project

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bibliotecasantateresa.database.AppDatabase

fun main() {

    val driver = JdbcSqliteDriver("jdbc:sqlite:biblioteca.db")

    // Solo crear si no existe
    try {
        AppDatabase.Schema.create(driver)
    } catch (_: Exception) {}

    val db = AppDatabase(driver)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Biblioteca Biblioteca Demo"
        ) {
            App(db)
        }
    }
}
