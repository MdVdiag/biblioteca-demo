package org.example.project.data

import java.sql.Connection

object DatabaseManager {
    var connection: Connection? = null
        private set

    fun init(conn: Connection) {
        connection = conn
    }
}