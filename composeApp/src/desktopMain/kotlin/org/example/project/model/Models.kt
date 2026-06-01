package org.example.project.model

data class FichaLibro(
    val isbn: String = "",
    val titulo: String = "",
    val autor: String = "",
    val portadaUrl: String? = null,
    val descripcion: String = "",
    val categorias: String = "",
    val publishedDate: String = "",
    val publisher: String = "",
    val estado: String = "Nuevo"
)

data class Prestamo(
    val id: Long,
    val ejemplarId: Long,
    val isbn: String,
    val alumno: String,
    val curso: String,
    val prestadoAt: Long,
    val vencimientoAt: Long?,
    val devueltoAt: Long?,
    val titulo: String = ""
)

data class LibroConEstado(
    val ejemplarId: Long = 0L,
    val libro: FichaLibro,
    val prestamoActivo: Prestamo?
)

data class AlumnoResumen(
    val nombre: String,
    val curso: String,
    val numPrestamosActivos: Int,
    val codigo: String? = null
) {
    val tienePrestamo: Boolean get() = numPrestamosActivos > 0
}