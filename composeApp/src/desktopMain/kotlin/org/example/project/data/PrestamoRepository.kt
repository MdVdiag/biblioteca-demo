package org.example.project.data

import org.example.project.model.Prestamo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PrestamoRepository {

    private fun getDb() = DatabaseManager.connection

    suspend fun getPrestamoActivo(ejemplarId: Long): Prestamo? = withContext(Dispatchers.IO) {
        val sql = """
            SELECT id, ejemplarId, isbn, alumno, curso, prestadoAt, vencimientoAt, devueltoAt
            FROM prestamos
            WHERE ejemplarId = ? AND devueltoAt IS NULL
            ORDER BY prestadoAt DESC LIMIT 1
        """.trimIndent()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            stmt.setLong(1, ejemplarId)
            val rs = stmt.executeQuery()
            if (rs.next()) return@withContext Prestamo(
                id = rs.getLong("id"),
                ejemplarId = rs.getLong("ejemplarId").takeIf { !rs.wasNull() } ?: ejemplarId,
                isbn = rs.getString("isbn"),
                alumno = rs.getString("alumno"),
                curso = rs.getString("curso"),
                prestadoAt = rs.getLong("prestadoAt"),
                vencimientoAt = rs.getLong("vencimientoAt").takeIf { !rs.wasNull() },
                devueltoAt = rs.getLong("devueltoAt").takeIf { !rs.wasNull() }
            )
        }
        null
    }

    suspend fun getHistoricoPrestamos(ejemplarId: Long, limit: Int = 50): List<Prestamo> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT id, ejemplarId, isbn, alumno, curso, prestadoAt, vencimientoAt, devueltoAt
            FROM prestamos WHERE ejemplarId = ?
            ORDER BY prestadoAt DESC LIMIT ?
        """.trimIndent()
        val out = mutableListOf<Prestamo>()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            stmt.setLong(1, ejemplarId)
            stmt.setInt(2, limit)
            val rs = stmt.executeQuery()
            while (rs.next()) out.add(Prestamo(
                id = rs.getLong("id"),
                ejemplarId = rs.getLong("ejemplarId").takeIf { !rs.wasNull() } ?: ejemplarId,
                isbn = rs.getString("isbn"),
                alumno = rs.getString("alumno"),
                curso = rs.getString("curso"),
                prestadoAt = rs.getLong("prestadoAt"),
                vencimientoAt = rs.getLong("vencimientoAt").takeIf { !rs.wasNull() },
                devueltoAt = rs.getLong("devueltoAt").takeIf { !rs.wasNull() }
            ))
        }
        out
    }

    suspend fun prestarLibro(ejemplarId: Long, isbn: String, alumno: String, curso: String, diasPrestamo: Int): Boolean =
        withContext(Dispatchers.IO) {

            val conn = getDb() ?: return@withContext false
            val now = System.currentTimeMillis()
            val venc = now + diasPrestamo.coerceAtLeast(1).toLong() * 24L * 60L * 60L * 1000L
            val nombreLimpio = alumno.trim()
            val cursoLimpio = curso.trim()
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "SELECT id FROM prestamos WHERE ejemplarId = ? AND devueltoAt IS NULL LIMIT 1"
                ).use { check ->
                    check.setLong(1, ejemplarId)
                    if (check.executeQuery().next()) {
                        conn.rollback()
                        return@withContext false
                    }
                }
                conn.prepareStatement(
                    "INSERT INTO prestamos (ejemplarId, isbn, alumno, curso, prestadoAt, vencimientoAt, devueltoAt) VALUES (?, ?, ?, ?, ?, ?, NULL)"
                ).use { ins ->
                    ins.setLong(1, ejemplarId)
                    ins.setString(2, isbn)
                    ins.setString(3, nombreLimpio)
                    ins.setString(4, cursoLimpio)
                    ins.setLong(5, now)
                    ins.setLong(6, venc)
                    ins.executeUpdate()
                }
                conn.prepareStatement(
                    "INSERT OR IGNORE INTO alumnos (nombre, curso) VALUES (?, ?)"
                ).use { insAlumno ->
                    insAlumno.setString(1, nombreLimpio)
                    insAlumno.setString(2, cursoLimpio)
                    insAlumno.executeUpdate()
                }
                conn.commit()
                true
            } catch (e: Exception) {
               conn.rollback()
                false
            } finally {
                conn.autoCommit = true
            }
        }

    suspend fun devolverLibro(ejemplarId: Long): Boolean = withContext(Dispatchers.IO) {
        val conn = getDb() ?: return@withContext false
        conn.prepareStatement(
            "UPDATE prestamos SET devueltoAt = ? WHERE ejemplarId = ? AND devueltoAt IS NULL"
        ).use { stmt ->
            stmt.setLong(1, System.currentTimeMillis())
            stmt.setLong(2, ejemplarId)
            stmt.executeUpdate() > 0
        }
    }

    suspend fun getPrestamosByAlumno(nombre: String, curso: String): List<Prestamo> = withContext(Dispatchers.IO) {
        val sql = "SELECT p.*, (SELECT l.titulo FROM libros l WHERE l.id = p.ejemplarId LIMIT 1) as titulo FROM prestamos p WHERE p.alumno = ? AND p.curso = ? AND p.devueltoAt IS NULL ORDER BY p.prestadoAt DESC"
        val out = mutableListOf<Prestamo>()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, nombre)
            stmt.setString(2, curso)
            val rs = stmt.executeQuery()
            while (rs.next()) out.add(Prestamo(
                id = rs.getLong("id"),
                ejemplarId = rs.getLong("ejemplarId").takeIf { !rs.wasNull() } ?: 0L,
                isbn = rs.getString("isbn"),
                alumno = rs.getString("alumno"),
                curso = rs.getString("curso"),
                prestadoAt = rs.getLong("prestadoAt"),
                vencimientoAt = rs.getLong("vencimientoAt").takeIf { !rs.wasNull() },
                devueltoAt = rs.getLong("devueltoAt").takeIf { !rs.wasNull() },
                titulo = rs.getString("titulo") ?: ""
            ))
        }
        out
    }
}