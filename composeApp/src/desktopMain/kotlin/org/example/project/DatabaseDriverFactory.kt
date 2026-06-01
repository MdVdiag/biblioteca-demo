package org.example.project

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

fun createDatabaseConnection(): Connection {
    // 1. Localizamos la carpeta AppData de Windows
    val appData = System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home")
    val appFolder = File(appData, "BibliotecaApp")

    // 2. Creamos la carpeta si no existe
    if (!appFolder.exists()) {
        appFolder.mkdirs()
    }

    // 3. Ruta al archivo .db
    val dbFile = File(appFolder, "biblioteca.db")
    val url = "jdbc:sqlite:${dbFile.absolutePath}"

    // 4. Retornamos la conexión
    return DriverManager.getConnection(url)
}