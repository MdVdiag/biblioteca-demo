package org.example.project.data

import org.example.project.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Types

class LibroRepository {

    private fun getDb() = DatabaseManager.connection

    suspend fun listarLibrosConEstado(
        campo: CampoBusqueda,
        texto: String,
        filtro: FiltroEstado,
        tematica: String? = null
    ): List<LibroConEstado> = withContext(Dispatchers.IO) {
        val q = texto.trim()
        val like = "%$q%"
        val whereBusqueda = if (q.isBlank()) "1=1" else when (campo) {
            CampoBusqueda.ISBN -> "l.isbn LIKE ?"
            CampoBusqueda.TITULO -> "IFNULL(l.titulo,'') LIKE ?"
            CampoBusqueda.AUTOR -> "IFNULL(l.autor,'') LIKE ?"
            CampoBusqueda.CATEGORIA -> "IFNULL(l.categorias,'') LIKE ?"
            CampoBusqueda.TODO -> "(l.isbn LIKE ? OR IFNULL(l.titulo,'') LIKE ? OR IFNULL(l.autor,'') LIKE ? OR IFNULL(l.categorias,'') LIKE ?)"
        }
        val now = System.currentTimeMillis()
        val whereFiltro = when (filtro) {
            FiltroEstado.TODOS -> "1=1"
            FiltroEstado.DISPONIBLES -> "p.id IS NULL"
            FiltroEstado.PRESTADOS -> "p.id IS NOT NULL"
            FiltroEstado.VENCIDOS -> "(p.id IS NOT NULL AND p.vencimientoAt IS NOT NULL AND p.vencimientoAt < ?)"
            FiltroEstado.NUEVO -> "UPPER(IFNULL(l.estado,'')) = 'NUEVO'"
            FiltroEstado.BUEN_ESTADO -> "UPPER(IFNULL(l.estado,'')) = 'BUEN ESTADO'"
            FiltroEstado.USADO -> "UPPER(IFNULL(l.estado,'')) = 'USADO'"
            FiltroEstado.DAÑADO -> "(UPPER(IFNULL(l.estado,'')) = 'DAÑADO' OR IFNULL(l.estado,'') = 'Dañado')"
        }
        val t = tematica?.trim().orEmpty()
        val whereTematica = if (t.isBlank()) "1=1" else "IFNULL(l.categorias,'') LIKE ?"
        val sql = """
            SELECT l.id, l.isbn, l.titulo, l.autor, l.portadaUrl, l.descripcion,
                   l.categorias, l.publishedDate, l.publisher, l.estado,
                   p.id AS prestamo_id, p.alumno AS prestamo_alumno,
                   p.curso AS prestamo_curso, p.prestadoAt AS prestamo_prestadoAt,
                   p.vencimientoAt AS prestamo_vencimientoAt,
                   p.devueltoAt AS prestamo_devueltoAt
            FROM libros l
            LEFT JOIN prestamos p ON p.ejemplarId = l.id AND p.devueltoAt IS NULL
            WHERE ($whereBusqueda) AND ($whereFiltro) AND ($whereTematica)
            ORDER BY l.titulo
        """.trimIndent()
        val out = mutableListOf<LibroConEstado>()
        getDb()?.prepareStatement(sql)?.use { stmt ->
            var idx = 1
            if (q.isNotBlank()) {
                when (campo) {
                    CampoBusqueda.TODO -> repeat(4) { stmt.setString(idx++, like) }
                    else -> stmt.setString(idx++, like)
                }
            }
            if (t.isNotBlank()) stmt.setString(idx++, "%$t%")
            if (filtro == FiltroEstado.VENCIDOS) stmt.setLong(idx++, now)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val portada = rs.getString("portadaUrl")?.takeIf { it.isNotBlank() }
                val ejemplarId = rs.getLong("id")
                val libro = FichaLibro(
                    isbn = rs.getString("isbn"),
                    titulo = rs.getString("titulo"),
                    autor = rs.getString("autor"),
                    portadaUrl = portada,
                    descripcion = rs.getString("descripcion"),
                    categorias = rs.getString("categorias"),
                    publishedDate = rs.getString("publishedDate"),
                    publisher = rs.getString("publisher"),
                    estado = rs.getString("estado") ?: "Nuevo"
                )
                val prestamoId = rs.getLong("prestamo_id").takeIf { !rs.wasNull() }
                val prestamo = if (prestamoId == null) null else {
                    val v = rs.getLong("prestamo_vencimientoAt").takeIf { !rs.wasNull() }
                    Prestamo(
                        id = prestamoId,
                        ejemplarId = ejemplarId,
                        isbn = libro.isbn,
                        alumno = rs.getString("prestamo_alumno"),
                        curso = rs.getString("prestamo_curso"),
                        prestadoAt = rs.getLong("prestamo_prestadoAt"),
                        vencimientoAt = v,
                        devueltoAt = rs.getLong("prestamo_devueltoAt").takeIf { !rs.wasNull() }
                    )
                }
                out.add(LibroConEstado(ejemplarId = ejemplarId, libro = libro, prestamoActivo = prestamo))
            }
        }
        out
    }

    suspend fun saveBook(libro: FichaLibro) = withContext(Dispatchers.IO) {
        getDb()?.prepareStatement(
            "INSERT INTO libros (isbn, titulo, autor, portadaUrl, descripcion, categorias, publishedDate, publisher, estado) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        )?.use { stmt ->
            stmt.setString(1, libro.isbn)
            stmt.setString(2, libro.titulo)
            stmt.setString(3, libro.autor)
            val portada = libro.portadaUrl?.takeIf { it.isNotBlank() }
            if (portada == null) stmt.setNull(4, Types.VARCHAR) else stmt.setString(4, portada)
            stmt.setString(5, libro.descripcion)
            stmt.setString(6, libro.categorias)
            stmt.setString(7, libro.publishedDate)
            stmt.setString(8, libro.publisher)
            stmt.setString(9, libro.estado)
            stmt.executeUpdate()
        }
    }

    suspend fun deleteEjemplarById(ejemplarId: Long) = withContext(Dispatchers.IO) {
        getDb()?.prepareStatement("DELETE FROM libros WHERE id = ?")?.use { stmt ->
            stmt.setLong(1, ejemplarId)
            stmt.executeUpdate()
        }
    }

    suspend fun actualizarEstadoLibro(ejemplarId: Long, nuevoEstado: String) = withContext(Dispatchers.IO) {
        getDb()?.prepareStatement("UPDATE libros SET estado = ? WHERE id = ?")?.use { stmt ->
            stmt.setString(1, nuevoEstado)
            stmt.setLong(2, ejemplarId)
            stmt.executeUpdate()
        }
    }

    suspend fun listarTematicasDisponibles(): List<String> = withContext(Dispatchers.IO) {
        val set = linkedSetOf<String>()
        getDb()?.prepareStatement(
            "SELECT categorias FROM libros WHERE categorias IS NOT NULL AND TRIM(categorias) <> ''"
        )?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val raw = rs.getString("categorias") ?: continue
                raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { set.add(it) }
            }
        }
        set.toList().sorted()
    }
}