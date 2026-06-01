package org.example.project.viewmodel

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.project.data.AlumnoRepository
import org.example.project.data.LibroRepository
import org.example.project.data.PrestamoRepository
import org.example.project.model.*

class AppViewModel(private val scope: CoroutineScope) {

    private val httpClient = HttpClient(CIO) {
        engine {
            requestTimeout = 30000
        }
    }
    private val libroRepo = LibroRepository()
    private val prestamoRepo = PrestamoRepository()
    private val alumnoRepo = AlumnoRepository()

    // ── Navegación ──
    var modo by mutableStateOf(ModoPantalla.INICIO)
        private set

    // ── Estado general ──
    var dbReady by mutableStateOf(false)
        private set
    var mostrarInformacion by mutableStateOf(false)

    // ── Añadir libros ──
    var isbn by mutableStateOf("")
    var ficha by mutableStateOf<FichaLibro?>(null)
    var loading by mutableStateOf(false)
    var esManual by mutableStateOf(false)
    var addMsg by mutableStateOf<String?>(null)

    // ── Biblioteca ──
    var libros by mutableStateOf(listOf<LibroConEstado>())
        private set
    var searchText by mutableStateOf("")
    var campo by mutableStateOf(CampoBusqueda.TODO)
    var filtro by mutableStateOf(FiltroEstado.TODOS)
    var tematicaSeleccionada by mutableStateOf<String?>(null)
    var tematicasDisponibles by mutableStateOf<List<String>>(emptyList())
        private set
    var campoMenuOpen by mutableStateOf(false)
    var filtroMenuOpen by mutableStateOf(false)
    var tematicaMenuOpen by mutableStateOf(false)

    // ── Ficha libro seleccionado ──
    var ejemplarSeleccionadoId by mutableStateOf<Long?>(null)
        private set
    var prestamoActivo by mutableStateOf<Prestamo?>(null)
        private set
    var historicoPrestamos by mutableStateOf(listOf<Prestamo>())
        private set
    var prestamoDialogOpen by mutableStateOf(false)
    var alumnoPrestamo by mutableStateOf("")
    var cursoPrestamo by mutableStateOf("")
    var diasPrestamo by mutableStateOf("14")
    var prestamoMsg by mutableStateOf<String?>(null)
    var deleteDialogOpen by mutableStateOf(false)
    var prestamoProcesando by mutableStateOf(false)
        private set

    // ── Alumnos ──
    var alumnos by mutableStateOf(listOf<AlumnoResumen>())
        private set
    var selectedCurso by mutableStateOf<String?>(null)
    var tutorTexto by mutableStateOf("")
    var alumnosMsg by mutableStateOf<String?>(null)
    var editAlumnoDialogOpen by mutableStateOf(false)
    var alumnoEditando by mutableStateOf<AlumnoResumen?>(null)
    var prestamosAlumno by mutableStateOf<List<Prestamo>>(emptyList())

    // ── Inicialización ──

    fun onDbReady() {
        dbReady = true
        scope.launch {
            libros = libroRepo.listarLibrosConEstado(CampoBusqueda.TODO, "", FiltroEstado.TODOS)
            alumnos = alumnoRepo.listarAlumnosConEstado()
        }
    }

    // ── Navegación

    fun navegarA(pantalla: ModoPantalla) {
        modo = pantalla
        when (pantalla) {
            ModoPantalla.AÑADIR_LIBROS -> {
                isbn = ""; ficha = null; loading = false; esManual = false
            }
            ModoPantalla.BIBLIOTECA -> {
                ficha = null
                if (dbReady) scope.launch {
                    tematicasDisponibles = libroRepo.listarTematicasDisponibles()
                }
            }
            ModoPantalla.ALUMNOS -> {
                alumnosMsg = null
                scope.launch { alumnos = alumnoRepo.listarAlumnosConEstado() }
            }
            else -> {}
        }
    }

    // ── Biblioteca ──

    fun recargarLibros() {
        scope.launch {
            libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
        }
    }

    fun onSearchChanged(texto: String) {
        searchText = texto
        if (!dbReady || modo != ModoPantalla.BIBLIOTECA) return
        scope.launch {
            delay(300)
            libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
        }
    }

    fun seleccionarLibro(libro: FichaLibro, ejId: Long) {
        ficha = libro
        ejemplarSeleccionadoId = ejId
        scope.launch {
            prestamoActivo = prestamoRepo.getPrestamoActivo(ejId)
            historicoPrestamos = prestamoRepo.getHistoricoPrestamos(ejId)
        }
    }

    fun cerrarFicha() {
        ficha = null
        ejemplarSeleccionadoId = null
        prestamoActivo = null
        historicoPrestamos = emptyList()
        prestamoMsg = null
    }

    fun confirmarPrestamo() {
        if (prestamoProcesando) return
        prestamoProcesando = true
        val ejId = ejemplarSeleccionadoId ?: run { prestamoProcesando = false; return }
        val isbn = ficha?.isbn ?: run { prestamoProcesando = false; return }
        scope.launch {
            val dias = diasPrestamo.toIntOrNull() ?: 14
            val ok = prestamoRepo.prestarLibro(ejId, isbn, alumnoPrestamo, cursoPrestamo, dias)
            prestamoMsg = if (ok) "Préstamo registrado." else "No se pudo registrar el préstamo."
            prestamoActivo = prestamoRepo.getPrestamoActivo(ejId)
            historicoPrestamos = prestamoRepo.getHistoricoPrestamos(ejId)
            libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
            prestamoDialogOpen = false
            cargarPrestamosAlumno(alumnoPrestamo, cursoPrestamo)
            prestamoProcesando = false
        }
    }

    fun guardarLibro(libro: FichaLibro) {
        scope.launch {
            libroRepo.saveBook(libro)
            recargarLibros()
        }
    }

    suspend fun buscarYGuardarLibro(isbn: String, estado: String): FichaLibro? {
        println("BUSCANDO ISBN: $isbn estado: $estado")
        val nueva = searchLibroCompleto(isbn, estado)
        println("RESULTADO: ${nueva.titulo}")
        return if (!nueva.titulo.startsWith("Error:")) {
            libroRepo.saveBook(nueva)
            nueva
        } else null
    }

    private suspend fun searchLibroCompleto(isbn: String, estado: String = "Nuevo"): FichaLibro = withContext(Dispatchers.IO) {
        try {
            val response = httpClient.get("https://openlibrary.org/api/books?bibkeys=ISBN:$isbn&format=json&jscmd=data") {
                header("User-Agent", "BibliotecaSantaTeresa/1.0")
            }
            val json = response.bodyAsText()
            val root = Json.parseToJsonElement(json).jsonObject
            val book = root["ISBN:$isbn"]?.jsonObject
                ?: return@withContext FichaLibro(isbn, "Error: No encontrado", "", null, "", "", "", "", estado)

            val titulo = book["title"]?.jsonPrimitive?.contentOrNull ?: "Sin título"
            val autores = book["authors"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString(", ") ?: "Desconocido"
            val descripcion = ""
            val categorias = book["subjects"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.take(3)?.joinToString(", ") ?: ""
            val publishedDate = book["publish_date"]?.jsonPrimitive?.contentOrNull ?: ""
            val publisher = book["publishers"]?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                ?.firstOrNull() ?: ""
            val portadaFinal = book["cover"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull
                ?: book["cover"]?.jsonObject?.get("medium")?.jsonPrimitive?.contentOrNull

            FichaLibro(isbn, titulo, autores, portadaFinal, descripcion, categorias, publishedDate, publisher, estado)
        } catch (e: Exception) {
            println("ERROR ISBN: ${e.message}")
            println("ERROR ISBN causa: ${e.cause}")
            FichaLibro(isbn, "Error: ${e.localizedMessage}", "", null, "", "", "", "")
        }
    }

    fun devolverLibro(ejId: Long) {
        scope.launch {
            prestamoRepo.devolverLibro(ejId)
            // Recargar préstamos del alumno
            prestamosAlumno = prestamoRepo.getPrestamosByAlumno(
                alumnoEditando?.nombre ?: "",
                alumnoEditando?.curso ?: ""
            )
            // Recargar biblioteca
            libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
            // Si hay ficha abierta, actualizarla
            ejemplarSeleccionadoId?.let { id ->
                prestamoActivo = prestamoRepo.getPrestamoActivo(id)
                historicoPrestamos = prestamoRepo.getHistoricoPrestamos(id)
            }
        }
    }

    fun eliminarEjemplar(ejId: Long) {
        scope.launch {
            libroRepo.deleteEjemplarById(ejId)
            cerrarFicha()
            libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
        }
    }

    fun actualizarEstadoLibro(ejId: Long, nuevoEstado: String) {
        scope.launch {
            libroRepo.actualizarEstadoLibro(ejId, nuevoEstado)
            libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
        }
    }

    // ── Alumnos ──

    fun importarAlumnos(curso: String, nuevosAlumnos: List<Pair<String, String?>>) {
        scope.launch {
            alumnoRepo.insertarAlumnosEnCurso(curso, nuevosAlumnos)
            alumnos = alumnoRepo.listarAlumnosConEstado()
        }
    }
    fun cargarPrestamosAlumno(nombre: String, curso: String) {
        scope.launch {
            prestamosAlumno = prestamoRepo.getPrestamosByAlumno(nombre, curso)
        }
    }

    fun recargarAlumnos() {
        scope.launch { alumnos = alumnoRepo.listarAlumnosConEstado() }
    }

    fun seleccionarCurso(curso: String?) {
        selectedCurso = curso
        if (curso != null) scope.launch { tutorTexto = alumnoRepo.obtenerTutor(curso) }
    }

    fun guardarTutor(curso: String, tutor: String) {
        scope.launch { alumnoRepo.guardarTutor(curso, tutor) }
    }

    fun eliminarAlumno(nombre: String, curso: String) {
        scope.launch {
            alumnoRepo.eliminarAlumno(nombre, curso)
            alumnos = alumnoRepo.listarAlumnosConEstado()
        }
    }

    fun eliminarAlumnosPorCurso(curso: String) {
        scope.launch {
            alumnoRepo.eliminarAlumnosPorCurso(curso)
            alumnos = alumnoRepo.listarAlumnosConEstado()
        }
    }

    fun editarAlumno(alumno: AlumnoResumen, nuevoNombre: String, nuevoCurso: String, nuevoCodigo: String?) {
        scope.launch {
            alumnoRepo.editarAlumno(alumno.nombre, alumno.curso, nuevoNombre, nuevoCurso, nuevoCodigo)
            alumnos = alumnoRepo.listarAlumnosConEstado()
        }
    }
    suspend fun obtenerPrestamosAlumno(nombre: String, curso: String): List<Prestamo> {
        return prestamoRepo.getPrestamosByAlumno(nombre, curso)
    }
    fun prestarLibroAAlumno(nombreAlumno: String, cursoAlumno: String, ejemplarId: Long, isbn: String, dias: Int = 14) {
        scope.launch {
            val ok = prestamoRepo.prestarLibro(ejemplarId, isbn, nombreAlumno, cursoAlumno, dias)
            if (ok) {
                libros = libroRepo.listarLibrosConEstado(campo, searchText, filtro, tematicaSeleccionada)
                alumnos = alumnoRepo.listarAlumnosConEstado()
                prestamosAlumno = prestamoRepo.getPrestamosByAlumno(nombreAlumno, cursoAlumno)
            }
        }
    }
}

