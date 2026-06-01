package org.example.project.data

import org.example.project.model.AlumnoResumen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AlumnoRepository {

    private fun getDb() = DatabaseManager.connection

    suspend fun listarAlumnosConEstado(): List<AlumnoResumen> = withContext(Dispatchers.IO) {
        val sql = """
        SELECT a.nombre, a.curso, a.codigo,
            COUNT(CASE WHEN p.devueltoAt IS NULL THEN 1 END) AS activos
        FROM alumnos a
        LEFT JOIN prestamos p ON p.alumno = a.nombre AND p.curso = a.curso AND p.devueltoAt IS NULL
        GROUP BY a.nombre, a.curso
        ORDER BY a.curso, a.nombre
    """.trimIndent()
        val out = mutableListOf<AlumnoResumen>()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) out.add(
                AlumnoResumen(
                    nombre = rs.getString("nombre"),
                    curso = rs.getString("curso"),
                    numPrestamosActivos = rs.getInt("activos"),
                    codigo = rs.getString("codigo")
                )
            )
        }
        out
    }

    suspend fun listarNombresAlumnosPorCursoFlexible(cursoSeleccionado: String): List<String> = withContext(Dispatchers.IO) {
        val cursos = cursosEquivalentes(cursoSeleccionado)
        val placeholders = cursos.joinToString(",") { "?" }
        val sql = "SELECT nombre FROM alumnos WHERE curso IN ($placeholders) ORDER BY nombre"
        val lista = mutableListOf<String>()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            cursos.forEachIndexed { i, c -> stmt.setString(i + 1, c) }
            val rs = stmt.executeQuery()
            while (rs.next()) lista.add(rs.getString("nombre"))
        }
        lista
    }

    suspend fun listarCursosConAlumnos(): List<String> = withContext(Dispatchers.IO) {
        val sql = "SELECT DISTINCT curso FROM alumnos ORDER BY curso"
        val out = mutableListOf<String>()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) out.add(rs.getString("curso"))
        }
        out.map { canonicalizarCurso(it) }.distinct().sorted()
    }

    suspend fun guardarTutor(curso: String, tutor: String) = withContext(Dispatchers.IO) {
        getDb()?.prepareStatement(
            "INSERT OR REPLACE INTO configuracion_cursos (curso, tutor) VALUES (?, ?)"
        )?.use {
            it.setString(1, curso)
            it.setString(2, tutor)
            it.executeUpdate()
        }
    }

    suspend fun obtenerTutor(curso: String): String = withContext(Dispatchers.IO) {
        getDb()?.prepareStatement(
            "SELECT tutor FROM configuracion_cursos WHERE curso = ?"
        )?.use {
            it.setString(1, curso)
            val rs = it.executeQuery()
            if (rs.next()) rs.getString("tutor") ?: "" else ""
        } ?: ""
    }

    suspend fun eliminarAlumno(nombre: String, curso: String) = withContext(Dispatchers.IO) {
        getDb()?.prepareStatement(
            "DELETE FROM alumnos WHERE nombre = ? AND curso = ?"
        )?.use {
            it.setString(1, nombre)
            it.setString(2, curso)
            it.executeUpdate()
        }
    }

    suspend fun editarAlumno(nombreOriginal: String, cursoOriginal: String, nuevoNombre: String, nuevoCurso: String, nuevoCodigo: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            getDb()?.prepareStatement(
                "UPDATE alumnos SET nombre = ?, curso = ?, codigo = ? WHERE nombre = ? AND curso = ?"
            )?.use { stmt ->
                stmt.setString(1, nuevoNombre)
                stmt.setString(2, nuevoCurso)
                stmt.setString(3, nuevoCodigo)
                stmt.setString(4, nombreOriginal)
                stmt.setString(5, cursoOriginal)
                stmt.executeUpdate() > 0
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    suspend fun eliminarAlumnosPorCurso(curso: String): Int = withContext(Dispatchers.IO) {
        val conn = getDb() ?: return@withContext 0
        val cursos = cursosEquivalentes(curso)
        val placeholders = cursos.joinToString(",") { "?" }
        val sql = "DELETE FROM alumnos WHERE curso IN ($placeholders)"
        conn.prepareStatement(sql).use { stmt ->
            cursos.forEachIndexed { i, c -> stmt.setString(i + 1, c) }
            stmt.executeUpdate()
        }
    }

    suspend fun insertarAlumnosEnCurso(curso: String, alumnos: List<Pair<String, String?>>): Int = withContext(Dispatchers.IO) {
        val conn = getDb() ?: return@withContext 0
        val limpios = alumnos.map { it.first.trim() to it.second?.trim() }
            .filter { it.first.isNotBlank() }.distinctBy { it.first }
        if (limpios.isEmpty()) return@withContext 0
        var inserted = 0
        conn.autoCommit = false
        try {
            conn.prepareStatement(
                "INSERT OR IGNORE INTO alumnos (nombre, curso, codigo) VALUES (?, ?, ?)"
            ).use { stmt ->
                for ((nombre, codigo) in limpios) {
                    stmt.setString(1, nombre)
                    stmt.setString(2, curso)
                    stmt.setString(3, codigo)
                    inserted += stmt.executeUpdate()
                }
            }
            conn.commit()
            inserted
        } catch (e: Exception) {
            conn.rollback()
            0
        } finally {
            conn.autoCommit = true
        }
    }

    private fun canonicalizarCurso(curso: String): String {
        val c = curso.trim().uppercase()
        val mapa = mapOf(
            "PRIMERO A" to "1A PRIMARIA", "PRIMERO B" to "1B PRIMARIA",
            "SEGUNDO A" to "2A PRIMARIA", "SEGUNDO B" to "2B PRIMARIA",
            "TERCERO A" to "3A PRIMARIA", "TERCERO B" to "3B PRIMARIA",
            "CUARTO A" to "4A PRIMARIA", "CUARTO B" to "4B PRIMARIA",
            "QUINTO A" to "5A PRIMARIA", "QUINTO B" to "5B PRIMARIA",
            "SEXTO A" to "6A PRIMARIA", "SEXTO B" to "6B PRIMARIA"
        )
        return mapa[c] ?: curso.trim().uppercase()
    }

    private fun cursosEquivalentes(cursoSeleccionado: String): List<String> {
        val canon = canonicalizarCurso(cursoSeleccionado)
        val inverso = mapOf(
            "1A PRIMARIA" to listOf("1A PRIMARIA", "PRIMERO A"),
            "1B PRIMARIA" to listOf("1B PRIMARIA", "PRIMERO B"),
            "2A PRIMARIA" to listOf("2A PRIMARIA", "SEGUNDO A"),
            "2B PRIMARIA" to listOf("2B PRIMARIA", "SEGUNDO B"),
            "3A PRIMARIA" to listOf("3A PRIMARIA", "TERCERO A"),
            "3B PRIMARIA" to listOf("3B PRIMARIA", "TERCERO B"),
            "4A PRIMARIA" to listOf("4A PRIMARIA", "CUARTO A"),
            "4B PRIMARIA" to listOf("4B PRIMARIA", "CUARTO B"),
            "5A PRIMARIA" to listOf("5A PRIMARIA", "QUINTO A"),
            "5B PRIMARIA" to listOf("5B PRIMARIA", "QUINTO B"),
            "6A PRIMARIA" to listOf("6A PRIMARIA", "SEXTO A"),
            "6B PRIMARIA" to listOf("6B PRIMARIA", "SEXTO B")
        )
        return inverso[canon] ?: listOf(canon)
    }
}