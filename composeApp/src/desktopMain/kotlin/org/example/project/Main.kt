package org.example.project

import org.example.project.viewmodel.AppViewModel
import org.example.project.data.DatabaseManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.get
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.Image
import java.io.File
import java.sql.Connection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.OptIn
import java.util.LinkedHashMap
import kotlin.concurrent.thread
import org.example.project.model.*


private val cacheDePortadas = object : LinkedHashMap<String, ImageBitmap>(400, 0.75f, true) {
    override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 400
}
private val httpClient = HttpClient(CIO)
private val NavButtonShape = RoundedCornerShape(24.dp)
private val NavButtonModifier = Modifier.height(52.dp).defaultMinSize(minWidth = 120.dp)


var dbConnection: Connection? = null

private val dateFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatMillis(ms: Long?): String = if (ms == null) "—" else dateFmt.format(Instant.ofEpochMilli(ms))

private fun isVencido(p: Prestamo?): Boolean {
    if (p == null) return false
    val v = p.vencimientoAt ?: return false
    return p.devueltoAt == null && System.currentTimeMillis() > v
}

private fun parseIsbns(input: String): List<String> {
    return input
        .split('\n', ',', ';', '\t', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { it.replace("-", "") }
        .distinct()
}

fun siguienteEstado(estadoActual: String): String {
    return when (estadoActual.uppercase()) {
        "NUEVO" -> "Buen estado"
        "BUEN ESTADO" -> "Usado"
        "USADO" -> "Dañado"
        "DAÑADO" -> "Nuevo"
        else -> "Nuevo"
    }
}

fun actualizarEstadoLibro(ejemplarId: Long, nuevoEstado: String) {
    dbConnection?.prepareStatement(
        "UPDATE libros SET estado = ? WHERE id = ?"
    )?.use { stmt ->
        stmt.setString(1, nuevoEstado)
        stmt.setLong(2, ejemplarId)
        stmt.executeUpdate()
    }
}

fun actualizarDatosLibro(ejemplarId: Long, titulo: String, autor: String, publishedDate: String, publisher: String, portadaUrl: String?, isbn: String) {
    val rows = DatabaseManager.connection?.prepareStatement(
        "UPDATE libros SET titulo = ?, autor = ?, publishedDate = ?, publisher = ?, portadaUrl = ?, isbn = ? WHERE id = ?"
    )?.use { stmt ->
        stmt.setString(1, titulo)
        stmt.setString(2, autor)
        stmt.setString(3, publishedDate)
        stmt.setString(4, publisher)
        stmt.setString(5, portadaUrl)
        stmt.setString(6, isbn)
        stmt.setLong(7, ejemplarId)
        stmt.executeUpdate()
    }
}
@Composable
fun PortadaRemota(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val placeholder = remember { ColorPainter(Color(0xFFE0E0E0)) }

    // Carpeta de caché en disco
    val carpetaCache = File(System.getProperty("user.home"), ".bibliotecacache/portadas")

    fun archivoCacheParaUrl(url: String): File {
        val nombre = url.filter { it.isLetterOrDigit() }.takeLast(60) + ".jpg"
        return File(carpetaCache, nombre)
    }

    // 1. Intentamos obtener la imagen de la memoria RAM primero
    val imagenEnCache = url?.let { cacheDePortadas[it] }

    val imageBitmap by produceState<ImageBitmap?>(initialValue = imagenEnCache, key1 = url) {
        if (url == null || imagenEnCache != null) return@produceState

        value = withContext(Dispatchers.IO) {
            try {
                // 2. Miramos si existe en disco
                val archivoDisco = archivoCacheParaUrl(url)
                val bitmapCargado = if (archivoDisco.exists()) {
                    // Carga desde disco (sin internet)
                    val bytes = archivoDisco.readBytes()
                    Image.makeFromEncoded(bytes).asImageBitmap()
                } else if (url.startsWith("http", ignoreCase = true)) {
                    // Descarga de internet y guarda en disco
                    carpetaCache.mkdirs()
                    val response = httpClient.get(url)
                    val bytes = response.readBytes()
                    archivoDisco.writeBytes(bytes) // guarda en disco
                    Image.makeFromEncoded(bytes).asImageBitmap()
                } else {
                    // Archivo local
                    val path = if (url.startsWith("file:", ignoreCase = true)) {
                        url.removePrefix("file:")
                    } else url
                    val file = File(path)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        Image.makeFromEncoded(bytes).asImageBitmap()
                    } else null
                }

                // 3. Guardamos en RAM también
                if (bitmapCargado != null) {
                    cacheDePortadas[url] = bitmapCargado
                }
                bitmapCargado

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Error cargando portada desde $url: ${e.message}")
                null
            }
        }
    }
    // 4. Dibujamos la imagen (si existe) o el cuadro gris
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = "Portada",
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Image(
            painter = placeholder,
            contentDescription = "Portada",
            modifier = modifier,
            contentScale = contentScale
        )
    }
}

fun deleteBook(isbn: String) {
    dbConnection?.prepareStatement("DELETE FROM libros WHERE isbn = ?")?.use { stmt ->
        stmt.setString(1, isbn)
        stmt.executeUpdate()
    }
}

fun deleteEjemplarById(ejemplarId: Long) {
    dbConnection?.prepareStatement("DELETE FROM libros WHERE id = ?")?.use { stmt ->
        stmt.setLong(1, ejemplarId)
        stmt.executeUpdate()
    }
}


suspend fun loadBooks(): List<FichaLibro> = withContext(Dispatchers.IO) {
    val libros = mutableListOf<FichaLibro>()
    dbConnection?.createStatement()?.use { stmt ->
        val rs = stmt.executeQuery(
            """
            SELECT isbn, titulo, autor, portadaUrl, descripcion, categorias, publishedDate, publisher, estado 
            FROM libros
        """
        )
        while (rs.next()) {
            val portada = rs.getString("portadaUrl")?.takeIf { it.isNotBlank() }
            libros.add(
                FichaLibro(
                    rs.getString("isbn"),
                    rs.getString("titulo"),
                    rs.getString("autor"),
                    portada,
                    rs.getString("descripcion"),
                    rs.getString("categorias"),
                    rs.getString("publishedDate"),
                    rs.getString("publisher"),
                    rs.getString("estado") ?: "Nuevo"
                )
            )
        }
    }
    libros
}

suspend fun getPrestamosByAlumno(nombre: String, curso: String): List<Prestamo> = withContext(Dispatchers.IO) {
    val sql = """
        SELECT p.* FROM prestamos p 
        WHERE p.alumno = ? AND p.curso = ? 
        ORDER BY p.prestadoAt DESC
    """.trimIndent()
    val out = mutableListOf<Prestamo>()
    dbConnection?.prepareStatement(sql)?.use { stmt ->
        stmt.setString(1, nombre)
        stmt.setString(2, curso)
        val rs = stmt.executeQuery()
        while (rs.next()) {
            out.add(Prestamo(
                id = rs.getLong("id"),
                ejemplarId = rs.getLong("ejemplarId").takeIf { !rs.wasNull() } ?: 0L,
                isbn = rs.getString("isbn"),
                alumno = rs.getString("alumno"),
                curso = rs.getString("curso"),
                prestadoAt = rs.getLong("prestadoAt"),
                vencimientoAt = rs.getLong("vencimientoAt").takeIf { !rs.wasNull() },
                devueltoAt = rs.getLong("devueltoAt").takeIf { !rs.wasNull() }
            ))
        }
    }
    out
}

suspend fun listarNombresAlumnosPorCurso(curso: String): List<String> = withContext(Dispatchers.IO) {
    val sql = "SELECT nombre FROM alumnos WHERE curso = ? ORDER BY nombre"
    val lista = mutableListOf<String>()
    dbConnection?.prepareStatement(sql)?.use { stmt ->
        stmt.setString(1, curso)
        val rs = stmt.executeQuery()
        while (rs.next()) {
            lista.add(rs.getString("nombre"))
        }
    }
    lista
}

private fun canonicalizarCurso(curso: String): String {
    val c = curso.trim().uppercase()
    val mapa = mapOf(
        "PRIMERO A" to "1A PRIMARIA",
        "PRIMERO B" to "1B PRIMARIA",
        "SEGUNDO A" to "2A PRIMARIA",
        "SEGUNDO B" to "2B PRIMARIA",
        "TERCERO A" to "3A PRIMARIA",
        "TERCERO B" to "3B PRIMARIA",
        "CUARTO A" to "4A PRIMARIA",
        "CUARTO B" to "4B PRIMARIA",
        "QUINTO A" to "5A PRIMARIA",
        "QUINTO B" to "5B PRIMARIA",
        "SEXTO A" to "6A PRIMARIA",
        "SEXTO B" to "6B PRIMARIA"
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

suspend fun obtenerTutor(curso: String): String = withContext(Dispatchers.IO) {
    dbConnection?.prepareStatement("SELECT tutor FROM configuracion_cursos WHERE curso = ?")?.use {
        it.setString(1, curso)
        val rs = it.executeQuery()
        if (rs.next()) rs.getString("tutor") ?: "" else ""
    } ?: ""
}

fun filtrarAlumnos(alumnos: List<AlumnoResumen>, curso: String?): List<AlumnoResumen> {
    return if (curso == null) alumnos.sortedBy { it.nombre }
    else alumnos.filter { it.curso == curso }.sortedBy { it.nombre }
}


@Composable
fun FichaCompleta(libro: FichaLibro, ejemplarId: Long = 0L, onLibroActualizado: () -> Unit = {}) {
    var modoEdicion by remember { mutableStateOf(false) }

    // Los Campos son editables
    var titulo by remember { mutableStateOf(libro.titulo) }
    var autor by remember { mutableStateOf(libro.autor) }
    var fecha by remember { mutableStateOf(libro.publishedDate ?: "") }
    var editorial by remember { mutableStateOf(libro.publisher ?: "") }
    var isbn by remember { mutableStateOf(libro.isbn ?: "") }
    var portadaUrl by remember { mutableStateOf(libro.portadaUrl ?: "") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PortadaRemota(
                    url = if (modoEdicion && portadaUrl.isNotBlank()) portadaUrl else libro.portadaUrl,
                    modifier = Modifier
                        .size(128.dp, 209.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )

                Column(modifier = Modifier.weight(1f)) {
                    if (modoEdicion) {
                        OutlinedTextField(value = titulo, onValueChange = { titulo = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(value = autor, onValueChange = { autor = it }, label = { Text("Autor") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(value = isbn, onValueChange = { isbn = it }, label = { Text("ISBN") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(value = fecha, onValueChange = { fecha = it }, label = { Text("Año") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(value = editorial, onValueChange = { editorial = it }, label = { Text("Editorial") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = portadaUrl,
                            onValueChange = { portadaUrl = it },
                            label = { Text("URL Portada") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val file = elegirImagenPortada()
                                    if (file != null) {
                                        portadaUrl = "file:${file.absolutePath}"
                                    }
                                }) {
                                    Icon(Icons.Default.Image, contentDescription = "Cargar imagen desde archivo")
                                }
                            }
                        )
                    } else {
                        Text(libro.titulo, style = MaterialTheme.typography.titleLarge)
                        Text(libro.autor, style = MaterialTheme.typography.titleMedium)
                        Text("${libro.publishedDate} • ${libro.publisher}", style = MaterialTheme.typography.bodyMedium)
                        Text(libro.categorias, style = MaterialTheme.typography.bodySmall)
                        Text("Estado: ${libro.estado}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (modoEdicion) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            actualizarDatosLibro(ejemplarId, titulo, autor, fecha, editorial, portadaUrl.ifBlank { null }, isbn)
                            modoEdicion = false
                            onLibroActualizado()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Guardar") }

                    OutlinedButton(
                        onClick = {
                            // Restaurar valores originales
                            titulo = libro.titulo
                            autor = libro.autor
                            fecha = libro.publishedDate ?: ""
                            editorial = libro.publisher ?: ""
                            isbn = libro.isbn ?: ""
                            portadaUrl = libro.portadaUrl ?: ""
                            modoEdicion = false
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancelar") }
                }
            } else {
                OutlinedButton(
                    onClick = { modoEdicion = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Editar ficha")
                }
                Spacer(Modifier.height(8.dp))
                Text(libro.descripcion, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun FichaAlumnoMejorada(
    viewModel: AppViewModel,
    alumno: AlumnoResumen,
    onDelete: () -> Unit
) {
    var prestamos by remember { mutableStateOf<List<Prestamo>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var mostrarDialogoPrestamo by remember { mutableStateOf(false) }
    var busquedaLibro by remember { mutableStateOf("") }
    var diasPrestamo by remember { mutableStateOf("14") }
    var mensajePrestamo by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(alumno.nombre, alumno.curso) {
        prestamos = viewModel.obtenerPrestamosAlumno(alumno.nombre, alumno.curso)
    }

    // Diálogo de nuevo préstamo
    if (mostrarDialogoPrestamo) {
        val librosDisponibles = viewModel.libros
            .filter { it.prestamoActivo == null }
            .filter { libro ->
                busquedaLibro.isBlank() ||
                        libro.libro.titulo.contains(busquedaLibro, ignoreCase = true) ||
                        libro.libro.autor.contains(busquedaLibro, ignoreCase = true) ||
                        libro.libro.isbn.contains(busquedaLibro, ignoreCase = true)
            }

        AlertDialog(
            onDismissRequest = {
                mostrarDialogoPrestamo = false
                busquedaLibro = ""
                mensajePrestamo = null
            },
            title = { Text("Nuevo préstamo para ${alumno.nombre}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = busquedaLibro,
                        onValueChange = { busquedaLibro = it },
                        label = { Text("Buscar libro") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = diasPrestamo,
                        onValueChange = { diasPrestamo = it },
                        label = { Text("Días de préstamo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    mensajePrestamo?.let {
                        Text(it, color = if (it.startsWith("✅")) Color(0xFF2E7D32) else Color(0xFFD32F2F))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Libros disponibles (${librosDisponibles.size}):",
                        style = MaterialTheme.typography.labelMedium)
                    Column(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (librosDisponibles.isEmpty()) {
                            Text("No hay libros disponibles", color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            librosDisponibles.forEach { libroConEstado ->
                                OutlinedButton(
                                    onClick = {
                                        val dias = diasPrestamo.toIntOrNull() ?: 14
                                        viewModel.prestarLibroAAlumno(
                                            alumno.nombre,
                                            alumno.curso,
                                            libroConEstado.ejemplarId,
                                            libroConEstado.libro.isbn,
                                            dias
                                        )
                                        scope.launch {
                                            prestamos = viewModel.obtenerPrestamosAlumno(alumno.nombre, alumno.curso)
                                        }
                                        mensajePrestamo = "✅ Préstamo registrado: ${libroConEstado.libro.titulo}"
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(libroConEstado.libro.titulo,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        Text("${libroConEstado.libro.autor} · ${libroConEstado.libro.publishedDate}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    mostrarDialogoPrestamo = false
                    busquedaLibro = ""
                    mensajePrestamo = null
                    scope.launch {
                        prestamos = viewModel.obtenerPrestamosAlumno(alumno.nombre, alumno.curso)
                    }
                }) { Text("Cerrar") }
            }
        )
    }

    var editando by remember { mutableStateOf(false) }
    var nuevoNombre by remember { mutableStateOf(alumno.nombre) }
    var nuevoCurso by remember { mutableStateOf(alumno.curso) }
    var nuevoCodigo by remember { mutableStateOf(alumno.codigo ?: "") }

    if (editando) {
        AlertDialog(
            onDismissRequest = { editando = false },
            title = { Text("Editar alumno") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nuevoNombre,
                        onValueChange = { nuevoNombre = it },
                        label = { Text("Nombre completo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nuevoCurso,
                        onValueChange = { nuevoCurso = it },
                        label = { Text("Curso") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = nuevoCodigo,
                        onValueChange = { nuevoCodigo = it },
                        label = { Text("Código de alumno") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.editarAlumno(alumno, nuevoNombre, nuevoCurso, nuevoCodigo.ifBlank { null })
                    editando = false
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { editando = false }) { Text("Cancelar") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            alumno.nombre.uppercase(),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!alumno.codigo.isNullOrBlank()) {
                            Text(
                                "Código: ${alumno.codigo}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                    Row {
                        TextButton(onClick = { editando = true }) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Editar")
                        }
                        TextButton(onClick = { mostrarDialogoPrestamo = true }) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Prestar")
                        }
                    }
                }

                if (prestamos.isEmpty()) {
                    Text("Sin préstamos activos",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray)
                } else {
                    prestamos.forEach { prestamo ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Text(
                                "${prestamo.titulo.ifBlank { prestamo.isbn }} · ${formatMillis(prestamo.prestadoAt)} → ${formatMillis(prestamo.vencimientoAt ?: 0L)}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = {
                                viewModel.devolverLibro(prestamo.ejemplarId)
                                scope.launch {
                                    kotlinx.coroutines.delay(300)
                                    prestamos = viewModel.obtenerPrestamosAlumno(alumno.nombre, alumno.curso)
                                }
                            }) { Text("Devolver") }
                        }
                    }
                }
            }

            var confirmarEliminar by remember { mutableStateOf(false) }

            if (confirmarEliminar) {
                AlertDialog(
                    onDismissRequest = { confirmarEliminar = false },
                    title = { Text("Eliminar alumno") },
                    text = { Text("¿Seguro que quieres eliminar a ${alumno.nombre} de ${alumno.curso}?") },
                    confirmButton = {
                        Button(
                            onClick = { confirmarEliminar = false; onDelete() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) { Text("Eliminar", color = MaterialTheme.colorScheme.onError) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmarEliminar = false }) { Text("Cancelar") }
                    }
                )
            }

            IconButton(onClick = { confirmarEliminar = true }) {
                Icon(Icons.Default.Delete,
                    contentDescription = "Eliminar",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
private val greenScheme = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = Color(0xFF66BB6A),
    onSecondary = Color.White,
    background = Color(0xFFF1F8E9),
    onBackground = Color(0xFF1B5E20),
    surface = Color.White,
    onSurface = Color(0xFF1B5E20),
    error = Color(0xFFD32F2F),
    onError = Color.White
)
@Composable
fun App() {
    var mostrarInformacion by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val vm = remember { AppViewModel(scope) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val addScroll = rememberScrollState()
    val fichaScroll = rememberScrollState()

    var isbn by vm::isbn
    var ficha by vm::ficha
    var loading by vm::loading
    var esManual by vm::esManual
    var addMsg by vm::addMsg
    val libros = vm.libros
    val alumnos = vm.alumnos
    var selectedCurso by vm::selectedCurso


    var alumnosOrdenados by remember { mutableStateOf<List<AlumnoResumen>>(emptyList()) }
    val tutorTexto = vm.tutorTexto
    var editAlumnoDialogOpen by remember { mutableStateOf(false) }
    var alumnoEditando by remember { mutableStateOf<AlumnoResumen?>(null) }
    var prestamosAlumno by remember { mutableStateOf<List<Prestamo>>(emptyList()) }
    var nuevoNombre by remember { mutableStateOf("") }
    var nuevoCurso by remember { mutableStateOf("") }
    var nuevoTutor by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var alumnosMsg by remember { mutableStateOf<String?>(null) }


    val dbReady = vm.dbReady
    var searchText by remember { mutableStateOf("") }
    var campo by remember { mutableStateOf(CampoBusqueda.TODO) }
    var campoMenuOpen by remember { mutableStateOf(false) }
    var filtro by remember { mutableStateOf(FiltroEstado.TODOS) }
    var filtroMenuOpen by remember { mutableStateOf(false) }
    var tematicaSeleccionada by remember { mutableStateOf<String?>(null) }
    var tematicaMenuOpen by remember { mutableStateOf(false) }
    var tematicasDisponibles by remember { mutableStateOf<List<String>>(emptyList()) }

    val prestamoActivo = vm.prestamoActivo
    val historicoPrestamos = vm.historicoPrestamos
    val prestamoDialogOpen = vm.prestamoDialogOpen
    val alumnoPrestamo = vm.alumnoPrestamo
    val cursoPrestamo = vm.cursoPrestamo
    val diasPrestamo = vm.diasPrestamo
    val prestamoMsg = vm.prestamoMsg
    val ejemplarSeleccionadoId = vm.ejemplarSeleccionadoId

    val modo = vm.modo
    var deleteDialogOpen by remember { mutableStateOf(false) }

    var mostrarAddDialog by remember { mutableStateOf(false) }
    var mostrarDeleteDialog by remember { mutableStateOf(false) }
    var alumnoSeleccionado by remember { mutableStateOf<AlumnoResumen?>(null) }

    var dialogAnadirVisible by remember { mutableStateOf(false) }
    var dialogEliminarVisible by remember { mutableStateOf(false) }

// Para eliminar: confirma nombre
    var seleccionado by remember { mutableStateOf<AlumnoResumen?>(alumnos.firstOrNull()) }


    LaunchedEffect(Unit) {
        val conn = withContext(Dispatchers.IO) {
            val c = createDatabaseConnection()
            c.createStatement().use { stmt ->
                stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS libros (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    isbn TEXT NOT NULL,
                    titulo TEXT,
                    autor TEXT,
                    portadaUrl TEXT,
                    descripcion TEXT,
                    categorias TEXT,
                    publishedDate TEXT,
                    publisher TEXT,
                    estado TEXT DEFAULT 'Nuevo'
                )
            """.trimIndent())
                stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS prestamos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ejemplarId INTEGER NOT NULL,
                    isbn TEXT,
                    alumno TEXT,
                    curso TEXT,
                    prestadoAt INTEGER,
                    vencimientoAt INTEGER,
                    devueltoAt INTEGER
                )
            """.trimIndent())
                stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS alumnos (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nombre TEXT NOT NULL,
                    curso TEXT NOT NULL,
                    UNIQUE(nombre, curso)
                )
            """.trimIndent())
                try {
                    stmt.executeUpdate("ALTER TABLE alumnos ADD COLUMN codigo TEXT")
                } catch (_: Exception) {

                }
                stmt.executeUpdate("""
                CREATE TABLE IF NOT EXISTS configuracion_cursos (
                    curso TEXT PRIMARY KEY,
                    tutor TEXT
                )
            """.trimIndent())
            }
            c
        }
        DatabaseManager.init(conn)
        vm.onDbReady()
        vm.recargarLibros()
        vm.recargarAlumnos()
    }

    LaunchedEffect(dbReady, vm.searchText, vm.campo, vm.filtro, modo, vm.tematicaSeleccionada) {
        if (!dbReady || modo != ModoPantalla.BIBLIOTECA) return@LaunchedEffect
        delay(300)
        vm.recargarLibros()
    }

    LaunchedEffect(modo) {
        if (modo == ModoPantalla.AÑADIR_LIBROS) {
            isbn = ""
            ficha = null
            loading = false
            esManual = false
        } else if (modo == ModoPantalla.BIBLIOTECA) {
            ficha = null
            if (dbReady) {
                vm.recargarLibros()
            }
        } else {
            alumnosMsg = null
        }
    }

    LaunchedEffect(ejemplarSeleccionadoId) {
        val ejId = ejemplarSeleccionadoId ?: return@LaunchedEffect


    }



    MaterialTheme(colorScheme = greenScheme) {
        if (!dbReady) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Image(painter = painterResource("logo_app.png"), contentDescription = null, modifier = Modifier.size(160.dp))
                    Text("Biblioteca escolar\nBiblioteca Demo de Jesús", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary)
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                    Text("Cargando...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource("estanterias.jpg"), // <--- Asegúrate de que el nombre sea exacto
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, // Esto hace que la imagen rellene toda la pantalla
                    alpha = 0.15f // Un 15% de opacidad para que sea una marca de agua suave
                )

                IconButton(
                    onClick = { mostrarInformacion = true },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Acerca de",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // ✅ POR ESTO
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = painterResource("san_enrique.png"),
                            contentDescription = "San Enrique",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(50.dp)),
                            contentScale = ContentScale.Crop,
                            alpha = 0.85f
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            "Biblioteca escolar\nBiblioteca Demo de Jesús\nSan Juan de Aznalfarache",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.width(16.dp))
                        Image(
                            painter = painterResource("santa_teresa.png"),
                            contentDescription = "Biblioteca Demo",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(50.dp)),
                            contentScale = ContentScale.Crop,
                            alpha = 0.85f
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {

                        Button(
                            onClick = { vm.navegarA(ModoPantalla.AÑADIR_LIBROS) },
                            modifier = NavButtonModifier,
                            shape = NavButtonShape,
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = if (modo == ModoPantalla.AÑADIR_LIBROS) 6.dp else 0.dp
                            ),
                            colors = if (modo == ModoPantalla.AÑADIR_LIBROS)
                                ButtonDefaults.buttonColors()
                            else
                                ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text(
                                "AÑADIR LIBROS",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { vm.navegarA(ModoPantalla.BIBLIOTECA) },
                            modifier = NavButtonModifier,
                            shape = NavButtonShape,
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = if (modo == ModoPantalla.BIBLIOTECA) 6.dp else 0.dp
                            ),
                            colors = if (modo == ModoPantalla.BIBLIOTECA)
                                ButtonDefaults.buttonColors()
                            else
                                ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text(
                                "BIBLIOTECA",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = {
                                vm.navegarA(ModoPantalla.ALUMNOS)
                                scope.launch { vm.recargarAlumnos() }
                            },
                            modifier = NavButtonModifier,
                            shape = NavButtonShape,
                            elevation = ButtonDefaults.buttonElevation(
                                defaultElevation = if (modo == ModoPantalla.ALUMNOS) 6.dp else 0.dp
                            ),
                            colors = if (modo == ModoPantalla.ALUMNOS)
                                ButtonDefaults.buttonColors()
                            else
                                ButtonDefaults.outlinedButtonColors()
                        ) {
                            Text(
                                "ALUMNOS/AS",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    when (modo) {
                        ModoPantalla.INICIO -> {
                            Spacer(Modifier.height(24.dp))
                        }

                        ModoPantalla.AÑADIR_LIBROS -> {

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(addScroll),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Añadir libros por ISBN", style = MaterialTheme.typography.titleLarge)

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = esManual, onCheckedChange = {
                                        esManual = it
                                        ficha = if (it) FichaLibro() else null
                                        if (!it) {
                                            isbn = ""
                                        }
                                    })
                                    Text("Activar Registro Manual")
                                }
                             // BLOQUE estado del libro
                                var expanded by remember { mutableStateOf(false) }
                                val opciones = listOf("Nuevo", "Buen estado", "Usado", "Dañado")
                                var estadoSeleccionado by remember { mutableStateOf("Nuevo") }


                                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                    Text("Estado del ejemplar:", style = MaterialTheme.typography.labelSmall)

                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        OutlinedButton(
                                            onClick = { expanded = true },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(estadoSeleccionado)
                                                Icon(imageVector = Icons.Default.ArrowDropDown, contentDescription = null)
                                            }
                                        }

                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            opciones.forEach { opcion ->
                                                DropdownMenuItem(
                                                    text = { Text(opcion) },
                                                    onClick = {
                                                        estadoSeleccionado = opcion
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                // FIN DEL BLOQUE
                                OutlinedTextField(
                                    value = isbn,
                                    onValueChange = {
                                        isbn = it
                                        addMsg = null
                                    },
                                    label = { Text("ISBN (Pulsa Intro para guardar)") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .onPreviewKeyEvent { keyEvent ->
                                            val isEnter = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                                            val isDown = keyEvent.type == KeyEventType.KeyDown

                                            if (isEnter && isDown) {
                                                val isbnParaGuardar = isbn.trim()

                                                if ((isbnParaGuardar.isNotBlank() || esManual) && !loading) {
                                                    loading = true
                                                    addMsg = null
                                                    focusManager.clearFocus()

                                                    // Usamos el MISMO scope que el botón para que no falle
                                                    scope.launch {
                                                        try {
                                                            if (esManual) {
                                                                val libroParaGuardar = ficha?.copy(isbn = isbnParaGuardar, estado = estadoSeleccionado)
                                                                if (libroParaGuardar != null && libroParaGuardar.titulo.isNotBlank()) {
                                                                    vm.guardarLibro(libroParaGuardar)
                                                                    isbn = ""
                                                                    focusRequester.requestFocus()
                                                                } else {
                                                                    addMsg = "Error: El título no puede estar vacío"
                                                                    loading = false
                                                                    return@launch
                                                                }
                                                            } else {
                                                                val nueva = vm.buscarYGuardarLibro(isbnParaGuardar, estadoSeleccionado)
                                                                if (nueva != null) {
                                                                    ficha = nueva
                                                                } else {
                                                                    addMsg = "Error al buscar el libro"
                                                                    loading = false
                                                                    return@launch
                                                                }
                                                            }
                                                            vm.recargarLibros()
                                                            isbn = ""
                                                            ficha = null
                                                            esManual = false
                                                        } catch (e: Exception) {
                                                            addMsg = "Error: ${e.message}"
                                                            e.printStackTrace()
                                                        } finally {
                                                            loading = false
                                                        }
                                                    }
                                                    true //Donde confirmamos que se captura el ENTER
                                                } else {
                                                    false
                                                }
                                            } else {
                                                false
                                            }
                                        },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                                )



                                ficha?.let { libroActual ->
                                    Column(
                                        modifier = Modifier.padding(top = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        HorizontalDivider()
                                        Text(
                                            "Datos del libro (puedes editarlos):",
                                            style = MaterialTheme.typography.titleMedium
                                        )

                                        OutlinedTextField(
                                            value = libroActual.titulo,
                                            onValueChange = { ficha = libroActual.copy(titulo = it) },
                                            label = { Text("Título del Libro") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = libroActual.autor,
                                            onValueChange = { ficha = libroActual.copy(autor = it) },
                                            label = { Text("Autor/es") },
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        OutlinedTextField(
                                            value = libroActual.portadaUrl ?: "",
                                            onValueChange = { ficha = libroActual.copy(portadaUrl = it) },
                                            label = { Text("URL de la Portada") },
                                            modifier = Modifier.fillMaxWidth(),
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    val file = elegirImagenPortada()
                                                    if (file != null) {
                                                        ficha = libroActual.copy(portadaUrl = "file:${file.absolutePath}")
                                                    }
                                                }) {
                                                    Icon(Icons.Default.Image, contentDescription = "Cargar imagen desde archivo")
                                                }
                                            }
                                        )

                                        OutlinedTextField(
                                            value = libroActual.descripcion,
                                            onValueChange = { ficha = libroActual.copy(descripcion = it) },
                                            label = { Text("Descripción / Resumen") },
                                            modifier = Modifier.fillMaxWidth(),
                                            minLines = 3
                                        )

                                        OutlinedTextField(
                                            value = libroActual.categorias,
                                            onValueChange = { ficha = libroActual.copy(categorias = it) },
                                            label = { Text("Temática / Categoría") },
                                            modifier = Modifier.fillMaxWidth(),
                                            placeholder = { Text("Ej: Aventuras, Infantil, Naturaleza…") }
                                        )

                                    }
                                }

                                Button(
                                    onClick = {
                                        if (loading) return@Button

                                        val isbnLimpio = isbn.trim()
                                        if (isbnLimpio.isBlank() && !esManual) return@Button

                                        loading = true
                                        addMsg = null
                                        scope.launch {
                                            try {
                                                if (esManual) {
                                                    val libroActual = ficha ?: FichaLibro()
                                                    vm.guardarLibro(
                                                        libroActual.copy(
                                                            isbn = isbnLimpio,
                                                            estado = estadoSeleccionado
                                                        )
                                                    )
                                                } else {
                                                    val nueva = vm.buscarYGuardarLibro(isbnLimpio, estadoSeleccionado)
                                                    if (nueva != null) {
                                                        ficha = nueva
                                                    } else {
                                                        addMsg = "Error al buscar el libro"
                                                        loading = false
                                                        return@launch
                                                    }
                                                }
                                                vm.recargarLibros()
                                                isbn = ""
                                                ficha = null
                                                esManual = false
                                            } catch (e: Exception) {
                                                addMsg = "Error: ${e.message}"
                                                e.printStackTrace()
                                            } finally {
                                                loading = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !loading
                                ) {
                                    Text(
                                        if (loading) "..." else if (esManual) "💾 Guardar" else "🔍 Buscar y guardar"
                                    )
                                }

                                addMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                                ficha?.let { libroSeleccionado ->
                                    val mostrarPreview =
                                        libroSeleccionado.titulo.isNotBlank() ||
                                                libroSeleccionado.autor.isNotBlank() ||
                                                (libroSeleccionado.portadaUrl?.isNotBlank() == true) ||
                                                libroSeleccionado.descripcion.isNotBlank() ||
                                                libroSeleccionado.categorias.isNotBlank() ||
                                                libroSeleccionado.publisher.isNotBlank() ||
                                                libroSeleccionado.publishedDate.isNotBlank()

                                    if (mostrarPreview) {
                                        Spacer(Modifier.height(16.dp))
                                        FichaCompleta(libroSeleccionado, onLibroActualizado = {
                                            scope.launch { vm.recargarLibros() }
                                        })
                                    }
                                }
                            }
                        }


                        ModoPantalla.ALUMNOS -> {
                            // 1. VARIABLES DE ESTADO
                            var showAddDialog by remember { mutableStateOf(false) }
                            var nuevoNombreAlumno by remember { mutableStateOf("") }
                            var textoBusquedaAlumno by remember { mutableStateOf("") }
                            var indexAEditar by remember { mutableStateOf(-1) }
                            var todosCursos by remember {
                                mutableStateOf(
                                    listOf(
                                        "1A PRIMARIA", "1B PRIMARIA",
                                        "2A PRIMARIA", "2B PRIMARIA",
                                        "3A PRIMARIA", "3B PRIMARIA",
                                        "4A PRIMARIA", "4B PRIMARIA",
                                        "5A PRIMARIA", "5B PRIMARIA",
                                        "6A PRIMARIA", "6B PRIMARIA"
                                    )
                                )
                            }

                            var cursoAEditar by remember { mutableStateOf<String?>(null) }
                            var nuevoNombreCurso by remember { mutableStateOf("") }

                            // 2. LÓGICA DE FILTRAD
                            val alumnosMostrados = remember(alumnos, selectedCurso, textoBusquedaAlumno) {
                                alumnos.filter { alumno ->
                                    val hayBusqueda = textoBusquedaAlumno.isNotBlank()
                                    val coincideCurso = hayBusqueda || selectedCurso == null || alumno.curso == selectedCurso
                                    val coincideNombre = alumno.nombre.contains(textoBusquedaAlumno, ignoreCase = true)
                                    val coincideCodigo = !alumno.codigo.isNullOrBlank() && alumno.codigo.contains(textoBusquedaAlumno, ignoreCase = true)
                                    coincideCurso && (textoBusquedaAlumno.isBlank() || coincideNombre || coincideCodigo)
                                }.sortedBy { it.nombre }
                            }

                            if (showAddDialog) {
                                AlertDialog(
                                    onDismissRequest = { showAddDialog = false },
                                    title = { Text("Nuevo Alumno en ${selectedCurso}") },
                                    text = {
                                        OutlinedTextField(
                                            value = nuevoNombreAlumno,
                                            onValueChange = { nuevoNombreAlumno = it },
                                            label = { Text("Apellidos y Nombre") },
                                            singleLine = true
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            if (nuevoNombreAlumno.isNotBlank()) {
                                                scope.launch {
                                                    val cursoParaAnadir = selectedCurso ?: run {
                                                        alumnosMsg = "Selecciona un curso primero."
                                                        return@launch
                                                    }
                                                    vm.importarAlumnos(cursoParaAnadir, listOf(nuevoNombreAlumno.trim() to null))
                                                    showAddDialog = false
                                                }
                                            }
                                        }) { Text("Guardar") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showAddDialog = false }) { Text("Cancelar") }
                                    }
                                )
                            }
// 3. Diseño de la pantalla
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                item {
                                    OutlinedTextField(
                                        value = textoBusquedaAlumno,
                                        onValueChange = { textoBusquedaAlumno = it },
                                        label = { Text("Buscar por nombre o código...") },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                        leadingIcon = { Icon(Icons.Default.Search, null) },
                                        trailingIcon = {
                                            if (textoBusquedaAlumno.isNotEmpty()) {
                                                IconButton(onClick = { textoBusquedaAlumno = "" }) {
                                                    Icon(Icons.Default.Close, null)
                                                }
                                            }
                                        },
                                        singleLine = true
                                    )
                                }

                                item {
                                    val cursosA = todosCursos.filter { it.contains("A PRIMARIA") }
                                    val cursosB = todosCursos.filter { it.contains("B PRIMARIA") }
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            cursosA.forEach { curso ->
                                                FilterChip(
                                                    selected = selectedCurso == curso,
                                                    onClick = {
                                                        vm.seleccionarCurso(if (selectedCurso == curso) null else curso)
                                                    },

                                                    label = { Text(curso) }
                                                )
                                            }
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            cursosB.forEach { curso ->
                                                FilterChip(
                                                    selected = selectedCurso == curso,
                                                    onClick = {
                                                        vm.seleccionarCurso(if (selectedCurso == curso) null else curso)
                                                    },

                                                    label = { Text(curso) }
                                                )
                                            }
                                        }
                                    }
                                }

                                // VENTANITA PARA CAMBIAR EL NOMBRE (DIÁLOGO)
                                item {
                                    if (cursoAEditar != null) {
                                        AlertDialog(
                                            onDismissRequest = { cursoAEditar = null },
                                            title = { Text("Editar nombre del curso") },
                                            text = {
                                                OutlinedTextField(
                                                    value = nuevoNombreCurso,
                                                    onValueChange = { nuevoNombreCurso = it.uppercase() },
                                                    label = { Text("Nuevo nombre (ej: TERCERO A)") },
                                                    singleLine = true
                                                )
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    scope.launch {
                                                        val nombreAntiguo = cursoAEditar // Guardamos el nombre que tenía
                                                        val nombreNuevo = nuevoNombreCurso.uppercase()

                                                        if (nombreAntiguo != null && nombreNuevo.isNotBlank()) {
                                                            dbConnection?.prepareStatement("UPDATE alumnos SET curso = ? WHERE curso = ?")
                                                                ?.use {
                                                                    it.setString(1, nombreNuevo)
                                                                    it.setString(2, nombreAntiguo)
                                                                    it.executeUpdate()
                                                                }

                                                            val listaNueva = todosCursos.toMutableList()
                                                            val idx = listaNueva.indexOf(nombreAntiguo)
                                                            if (idx != -1) listaNueva[idx] = nombreNuevo
                                                            todosCursos = listaNueva.sorted()

                                                            if (selectedCurso == nombreAntiguo) {
                                                                selectedCurso = nombreNuevo
                                                            }

                                                            vm.recargarAlumnos()
                                                            cursoAEditar = null
                                                        }
                                                    }
                                                }) { Text("Guardar y Reordenar") }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { cursoAEditar = null }) { Text("Cancelar") }
                                            }
                                        )
                                    }
                                }

                                if (selectedCurso != null || textoBusquedaAlumno.isNotBlank() || showAddDialog) {
                                    item {
                                        Card(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                        ) {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(
                                                    "GESTIÓN: ${selectedCurso}",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )

                                                OutlinedTextField(
                                                    value = vm.tutorTexto,
                                                    onValueChange = {
                                                        vm.tutorTexto = it
                                                        // ✅ POR ESTO
                                                        vm.guardarTutor(selectedCurso!!, it)
                                                    },
                                                    label = { Text("Tutor/a del Curso") },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    singleLine = true
                                                )

                                                Button(
                                                    onClick = {
                                                        nuevoNombreAlumno = ""
                                                        showAddDialog = true
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text("+ Añadir Alumno/a Manual")
                                                }

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        onClick = {
                                                            val curso = selectedCurso ?: return@Button
                                                            alumnosMsg = null
                                                            thread(isDaemon = true, name = "import-alumnos") {
                                                                val file = elegirArchivoAlumnos()
                                                                scope.launch {
                                                                    try {
                                                                        if (file == null) {
                                                                            alumnosMsg = "Importación cancelada."
                                                                            return@launch
                                                                        }
                                                                        val nombres = withContext(Dispatchers.IO) {
                                                                            leerNombresAlumnosDesdeArchivo(file)
                                                                        }
                                                                        if (nombres.isEmpty()) {
                                                                            alumnosMsg = "No se encontraron nombres en el archivo."
                                                                            return@launch
                                                                        }
                                                                        vm.importarAlumnos(curso, nombres)
                                                                        alumnosMsg = "Importados alumnos en $curso."
                                                                    } catch (e: Exception) {
                                                                        alumnosMsg = "Error importando: ${e.message ?: "desconocido"}"
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                                                    ) {
                                                        Text("📁 Importar Excel/CSV", color = Color.White)
                                                    }

                                                    FilledIconButton(
                                                        onClick = { showDeleteConfirm = true },
                                                        modifier = Modifier.size(44.dp),
                                                        colors = IconButtonDefaults.filledIconButtonColors(
                                                            containerColor = MaterialTheme.colorScheme.error,
                                                            contentColor = MaterialTheme.colorScheme.onError
                                                        )
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Borrar alumnos del curso")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        alumnosMsg?.let { msg ->
                                            Text(
                                                msg,
                                                modifier = Modifier.padding(horizontal = 16.dp),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    item {
                                        if (showDeleteConfirm) {
                                            AlertDialog(
                                                onDismissRequest = { showDeleteConfirm = false },
                                                title = { Text("Borrar alumnos del curso") },
                                                text = {
                                                    Text("¿Seguro que quieres borrar TODOS los alumnos de ${selectedCurso}? Esta acción no se puede deshacer.")
                                                },
                                                confirmButton = {
                                                    Button(
                                                        onClick = {
                                                            val curso = selectedCurso ?: return@Button
                                                            scope.launch {
                                                                vm.eliminarAlumnosPorCurso(curso)
                                                                showDeleteConfirm = false
                                                            }
                                                        },
                                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                                    ) { Text("Borrar", color = MaterialTheme.colorScheme.onError) }
                                                },
                                                dismissButton = {
                                                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") }
                                                }
                                            )
                                        }
                                    }

                                    if (alumnosMostrados.isEmpty()) {
                                        item {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    if (textoBusquedaAlumno.isNotBlank()) "No se encontró ningún alumno con ese código o nombre"
                                                    else "No hay alumnos en este curso",
                                                    color = Color.Gray
                                                )
                                            }
                                        }
                                    } else {
                                        items(alumnosMostrados) { alumno ->
                                            Box(Modifier.padding(horizontal = 16.dp)) {
                                                FichaAlumnoMejorada(
                                                    viewModel = vm,
                                                    alumno = alumno,
                                                    onDelete = {
                                                        vm.eliminarAlumno(alumno.nombre, alumno.curso)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        if (showAddDialog) {
                                            AlertDialog(
                                                onDismissRequest = { showAddDialog = false },
                                                title = { Text("Nuevo Alumno en ${selectedCurso}") },
                                                text = {
                                                    OutlinedTextField(
                                                        value = nuevoNombreAlumno,
                                                        onValueChange = { nuevoNombreAlumno = it },
                                                        label = { Text("Apellidos y Nombre") },
                                                        singleLine = true
                                                    )
                                                },
                                                confirmButton = {
                                                    Button(onClick = {
                                                        if (nuevoNombreAlumno.isNotBlank()) {
                                                            scope.launch {
                                                                val cursoParaAnadir = selectedCurso ?: run { alumnosMsg = "Selecciona un curso primero."; return@launch }
                                                                vm.importarAlumnos(cursoParaAnadir, listOf(nuevoNombreAlumno.trim() to null))
                                                                showAddDialog = false
                                                            }
                                                        }
                                                    }) { Text("Guardar") }
                                                },
                                                dismissButton = {
                                                    TextButton(onClick = { showAddDialog = false }) { Text("Cancelar") }
                                                }
                                            )
                                        }
                                    }

// DIÁLOGO PARA AÑADIR NUEVO ALUMNO
                                item {
                                    if (showAddDialog) {
                                        AlertDialog(
                                            onDismissRequest = { showAddDialog = false },
                                            title = { Text("Nuevo Alumno en ${selectedCurso}") },
                                            text = {
                                                OutlinedTextField(
                                                    value = nuevoNombreAlumno,
                                                    onValueChange = { nuevoNombreAlumno = it },
                                                    label = { Text("Apellidos y Nombre") },
                                                    singleLine = true
                                                )
                                            },
                                            confirmButton = {
                                                Button(onClick = {
                                                    if (nuevoNombreAlumno.isNotBlank()) {
                                                        scope.launch {
                                                            val cursoParaAnadir = selectedCurso ?: run { alumnosMsg = "Selecciona un curso primero."; return@launch }
                                                            vm.importarAlumnos(cursoParaAnadir, listOf(nuevoNombreAlumno.trim() to null))
                                                            showAddDialog = false
                                                        }
                                                    }
                                                }) { Text("Guardar") }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { showAddDialog = false }) { Text("Cancelar") }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        }
                        ModoPantalla.BIBLIOTECA -> {
                            ficha?.let { libroSeleccionado ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(fichaScroll),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Ficha seleccionada", style = MaterialTheme.typography.titleLarge)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = {
                                                ficha = null
                                                vm.cerrarFicha()
                                            }) {
                                                Text("Cerrar ficha")
                                            }
                                            TextButton(onClick = { deleteDialogOpen = true }) {
                                                Text("Eliminar libro", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }

                                    FichaCompleta(libroSeleccionado, vm.ejemplarSeleccionadoId ?: 0L, onLibroActualizado = {
                                        scope.launch {
                                            vm.recargarLibros()
                                            val ejId = vm.ejemplarSeleccionadoId
                                            if (ejId != null) {
                                                val libroActualizado = vm.libros.find { it.ejemplarId == ejId }?.libro
                                                if (libroActualizado != null) {
                                                    vm.seleccionarLibro(libroActualizado, ejId)
                                                }
                                            }
                                        }
                                    })

                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text("Préstamo", style = MaterialTheme.typography.titleLarge)

                                            prestamoMsg?.let { msg ->
                                                Text(msg, color = MaterialTheme.colorScheme.primary)
                                            }

                                            val activo = prestamoActivo
                                            if (activo == null) {
                                                Text("Estado: Disponible")
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Button(onClick = {
                                                        vm.recargarAlumnos()
                                                        vm.alumnoPrestamo = ""
                                                        vm.cursoPrestamo = ""
                                                        vm.prestamoMsg = null
                                                        vm.prestamoDialogOpen = true
                                                    }) { Text("Prestar") }
                                                }
                                            } else {
                                                val vencido = isVencido(activo)
                                                Text(
                                                    "Estado: ${if (vencido) "Vencido" else "Prestado"}",
                                                    color = if (vencido) MaterialTheme.colorScheme.error else Color.Unspecified
                                                )
                                                Text("Alumno: ${activo.alumno} • Curso: ${activo.curso}")
                                                Text("Prestado: ${formatMillis(activo.prestadoAt)}")
                                                Text("Vence: ${formatMillis(activo.vencimientoAt)}")
                                                Button(onClick = {
                                                    scope.launch {
                                                        val ejId = ejemplarSeleccionadoId
                                                        if (ejId != null) vm.devolverLibro(ejId)
                                                        val ok = ejId != null
                                                        vm.prestamoMsg =
                                                            if (ok) "Devolución registrada." else "No se pudo registrar la devolución."
                                                        if (ejId != null) {
                                                            vm.seleccionarLibro(libroSeleccionado, ejId!!)
                                                        }
                                                        vm.recargarLibros()
                                                    }
                                                }) { Text("Marcar como devuelto") }
                                            }

                                            HorizontalDivider()
                                            Text("Histórico", style = MaterialTheme.typography.titleMedium)
                                            if (historicoPrestamos.isEmpty()) {
                                                Text("Aún no hay préstamos registrados.")
                                            } else {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    historicoPrestamos.take(10).forEach { p ->
                                                        val dev = p.devueltoAt
                                                        Text(
                                                            "• ${p.alumno} (${p.curso}) — ${formatMillis(p.prestadoAt)} → ${
                                                                formatMillis(
                                                                    dev
                                                                )
                                                            } (vence ${formatMillis(p.vencimientoAt)})"
                                                        )
                                                    }
                                                    if (historicoPrestamos.size > 10) {
                                                        Text("Mostrando 10 de ${historicoPrestamos.size}.")
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (editAlumnoDialogOpen && alumnoEditando != null) {
                                        val alumno = alumnoEditando!!
                                        var nombreEdit by remember { mutableStateOf(alumno.nombre) }
                                        var cursoEdit by remember { mutableStateOf(alumno.curso) }

                                        AlertDialog(
                                            onDismissRequest = { editAlumnoDialogOpen = false },
                                            title = { Text("Editar ${alumno.nombre}") },
                                            text = {
                                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    OutlinedTextField(
                                                        value = nombreEdit,
                                                        onValueChange = { nombreEdit = it },
                                                        label = { Text("Nombre") })
                                                    OutlinedTextField(
                                                        value = cursoEdit,
                                                        onValueChange = { cursoEdit = it },
                                                        label = { Text("Curso") })
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth()
                                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween // Esto empuja el botón a la derecha
                                                    ) {
                                                        // Tu texto original
                                                        Text(
                                                            text = "Préstamos (${prestamosAlumno.size})",
                                                            style = MaterialTheme.typography.titleMedium
                                                        )

                                                        // EL BOTÓN DE EXCEL
                                                        Button(
                                                            onClick = {
                                                                // Convertimos tus datos reales a lista de listas para el Excel
                                                                val datosParaExcel = prestamosAlumno.map { p ->
                                                                    listOf(
                                                                        p.alumno,
                                                                        formatMillis(p.prestadoAt),
                                                                        formatMillis(p.vencimientoAt)
                                                                    )
                                                                }
                                                                exportarPrestamosAExcel(datosParaExcel)

                                                            },
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = Color(
                                                                    0xFF2E7D32
                                                                )
                                                            ) // Verde Excel
                                                        ) {
                                                            Icon(Icons.Default.Share, contentDescription = null)
                                                            Spacer(Modifier.width(8.dp))
                                                            Text("Exportar Excel")
                                                        }
                                                    }

                                                    LazyColumn {
                                                        items(prestamosAlumno.take(5)) { p ->
                                                            Text("- ${p.isbn} | ${formatMillis(p.prestadoAt)} → ${formatMillis(p.devueltoAt) ?: "Prestado"}")
                                                        }
                                                    }
                                                }
                                            },
                                            confirmButton = {
                                                Button(onClick = {

                                                    editAlumnoDialogOpen = false
                                                    alumnoEditando = null
                                                }) { Text("Guardar") }
                                            },
                                            dismissButton = {
                                                Button(onClick = { editAlumnoDialogOpen = false }) { Text("Cancelar") }
                                                Button(colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                    onClick = {

                                                        editAlumnoDialogOpen = false
                                                    }) { Text("Eliminar") }
                                            }
                                        )
                                    }


                                    if (prestamoDialogOpen) {
                                        var alumnosDelCurso by remember { mutableStateOf<List<String>>(emptyList()) }
                                        var alumnoMenuOpen by remember { mutableStateOf(false) }
                                        var cursosDisponibles by remember { mutableStateOf<List<String>>(emptyList()) }
                                        var cursoMenuOpen by remember { mutableStateOf(false) }

                                        LaunchedEffect(vm.alumnos) {
                                            cursosDisponibles = vm.alumnos.map { it.curso }.filter { it.isNotBlank() }.distinct()
                                        }

                                        // Cada vez que cambie el curso cambiamos sus alumnos
                                        LaunchedEffect(cursoPrestamo) {
                                            if (cursoPrestamo.isNotBlank()) {
                                                alumnosDelCurso = vm.alumnos.filter { it.curso == cursoPrestamo }.map { it.nombre }
                                            }
                                        }

                                        AlertDialog(
                                            onDismissRequest = { vm.prestamoDialogOpen = false },
                                            title = { Text("Prestar libro") },
                                            text = {
                                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                                    // SELECTOR DE CURSO
                                                    Box {
                                                        OutlinedTextField(
                                                            value = if (cursoPrestamo.isEmpty()) "Selecciona curso" else cursoPrestamo,
                                                            onValueChange = {},
                                                            readOnly = true,
                                                            label = { Text("Curso") },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            trailingIcon = {
                                                                IconButton(onClick = { cursoMenuOpen = true }) {
                                                                    Icon(Icons.Default.ArrowDropDown, null)
                                                                }
                                                            }
                                                        )
                                                        DropdownMenu(
                                                            expanded = cursoMenuOpen,
                                                            onDismissRequest = { cursoMenuOpen = false }) {
                                                            cursosDisponibles.forEach { c ->
                                                                DropdownMenuItem(text = { Text(c) }, onClick = {
                                                                    vm.cursoPrestamo = c
                                                                    vm.alumnoPrestamo = ""
                                                                    cursoMenuOpen = false
                                                                })
                                                            }
                                                        }
                                                    }

                                                    // SELECTOR DE ALUMNO
                                                    Box {
                                                        OutlinedTextField(
                                                            value = if (alumnoPrestamo.isEmpty()) "Selecciona alumno" else alumnoPrestamo,
                                                            onValueChange = {},
                                                            readOnly = true,
                                                            enabled = cursoPrestamo.isNotBlank(),
                                                            label = { Text("Alumno/a") },
                                                            modifier = Modifier.fillMaxWidth(),
                                                            trailingIcon = {
                                                                IconButton(onClick = { alumnoMenuOpen = true }) {
                                                                    Icon(Icons.Default.ArrowDropDown, null)
                                                                }
                                                            }
                                                        )
                                                        if (alumnosDelCurso.isNotEmpty()) {
                                                            DropdownMenu(
                                                                expanded = alumnoMenuOpen,
                                                                onDismissRequest = { alumnoMenuOpen = false }) {
                                                                alumnosDelCurso.forEach { nombre ->
                                                                    DropdownMenuItem(text = { Text(nombre) }, onClick = {
                                                                        vm.alumnoPrestamo = nombre
                                                                        alumnoMenuOpen = false
                                                                    })
                                                                }
                                                            }
                                                        }
                                                    }

                                                    OutlinedTextField(
                                                        value = diasPrestamo,
                                                        onValueChange = {
                                                            vm.diasPrestamo = it.filter { ch -> ch.isDigit() }.take(3)
                                                        },
                                                        label = { Text("Días de préstamo") },
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                }
                                            },
                                            confirmButton = {
                                                Button(
                                                    enabled = alumnoPrestamo.isNotBlank() && cursoPrestamo.isNotBlank(),
                                                    onClick = {
                                                        vm.confirmarPrestamo()
                                                    }
                                                ) { Text("Confirmar Préstamo") }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { vm.prestamoDialogOpen = false }) { Text("Cancelar") }
                                            }
                                        )
                                    }

                                    if (deleteDialogOpen) {
                                        AlertDialog(
                                            onDismissRequest = { deleteDialogOpen = false },
                                            title = { Text("Eliminar libro") },
                                            text = {
                                                Text("¿Seguro que quieres eliminar este ejemplar?")
                                            },
                                            confirmButton = {
                                                Button(
                                                    onClick = {
                                                        scope.launch {
                                                            val id = ejemplarSeleccionadoId
                                                            if (id != null) {
                                                                vm.eliminarEjemplar(id)
                                                            }
                                                            deleteDialogOpen = false
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = MaterialTheme.colorScheme.error
                                                    )
                                                ) {
                                                    Text("Eliminar", color = MaterialTheme.colorScheme.onError)
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = { deleteDialogOpen = false }) { Text("Cancelar") }
                                            }
                                        )
                                    }
                                }
                            }

                            Text("Buscar en tu biblioteca", style = MaterialTheme.typography.titleLarge)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = vm.searchText,
                                    onValueChange = { vm.onSearchChanged(it) },
                                    label = { Text("Texto de búsqueda") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                Box {
                                    val label = tematicaSeleccionada ?: "Todas"
                                    OutlinedButton(onClick = { tematicaMenuOpen = true }) { Text("Temática: $label") }
                                    DropdownMenu(
                                        expanded = tematicaMenuOpen,
                                        onDismissRequest = { tematicaMenuOpen = false }
                                    ) {
                                        DropdownMenuItem(text = { Text("Todas") }, onClick = {
                                            vm.tematicaSeleccionada = null
                                            tematicaMenuOpen = false
                                        })
                                        if (tematicasDisponibles.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text("— Sin temáticas —") },
                                                onClick = { tematicaMenuOpen = false },
                                                enabled = false
                                            )
                                        } else {
                                            tematicasDisponibles.forEach { t ->
                                                DropdownMenuItem(text = { Text(t) }, onClick = {
                                                    tematicaSeleccionada = t
                                                    tematicaMenuOpen = false
                                                })
                                            }
                                        }
                                    }
                                }

                                Box {
                                    val label = when (filtro) {
                                        FiltroEstado.TODOS -> "Todos"
                                        FiltroEstado.DISPONIBLES -> "Disponibles"
                                        FiltroEstado.PRESTADOS -> "Prestados"
                                        FiltroEstado.VENCIDOS -> "Vencidos"
                                        FiltroEstado.NUEVO -> "Nuevo"
                                        FiltroEstado.BUEN_ESTADO -> "Buen estado"
                                        FiltroEstado.USADO -> "Usado"
                                        FiltroEstado.DAÑADO -> "Dañado"
                                    }
                                    OutlinedButton(onClick = { filtroMenuOpen = true }) { Text("Estado: $label") }
                                    DropdownMenu(
                                        expanded = filtroMenuOpen,
                                        onDismissRequest = { filtroMenuOpen = false }
                                    ) {
                                        DropdownMenuItem(text = { Text("Todos") }, onClick = { vm.filtro = FiltroEstado.TODOS; filtroMenuOpen = false })
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("Disponibles") }, onClick = { vm.filtro = FiltroEstado.DISPONIBLES; filtroMenuOpen = false })
                                        DropdownMenuItem(text = { Text("Prestados") }, onClick = { vm.filtro = FiltroEstado.PRESTADOS; filtroMenuOpen = false })
                                        DropdownMenuItem(text = { Text("Vencidos") }, onClick = { vm.filtro = FiltroEstado.VENCIDOS; filtroMenuOpen = false })
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("Nuevo") }, onClick = { vm.filtro = FiltroEstado.NUEVO; filtroMenuOpen = false })
                                        DropdownMenuItem(text = { Text("Buen estado") }, onClick = { vm.filtro = FiltroEstado.BUEN_ESTADO; filtroMenuOpen = false })
                                        DropdownMenuItem(text = { Text("Usado") }, onClick = { vm.filtro = FiltroEstado.USADO; filtroMenuOpen = false })
                                        DropdownMenuItem(text = { Text("Dañado") }, onClick = { vm.filtro = FiltroEstado.DAÑADO; filtroMenuOpen = false })
                                    }
                                }


                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = {
                                        // Usamos la lista de libros que tienes en pantalla (Cámbialo por tu nombre de lista)
                                        val datosParaExcel = libros.map { it ->
                                            listOf(
                                                it.libro.titulo,                             // Columna 1: Libro
                                                it.prestamoActivo?.alumno ?: "Disponible",   // Columna 2: Alumno
                                                it.prestamoActivo?.vencimientoAt?.let { v -> formatMillis(v) }
                                                    ?: "-", // Columna 3: Fecha
                                                it.libro.estado              // Columna 4: Estado
                                            )
                                        }
                                        exportarPrestamosAExcel(datosParaExcel)

                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)), // Verde Excel
                                    shape = RoundedCornerShape(20.dp) // Redondeado como tus otros botones
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Excel")
                                }
                            }

                            Text("Libros:", style = MaterialTheme.typography.titleLarge)
                            Box(modifier = Modifier.weight(1f)) {
                                val scrollState = rememberLazyListState()
                                LazyColumn(modifier = Modifier.fillMaxSize(), state = scrollState) {
                                items(libros, key = { it.ejemplarId }) { libro ->
                                    val p = libro.prestamoActivo
                                    val vencido = remember(p?.vencimientoAt, p?.devueltoAt) { isVencido(p) }
                                    Card(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                vm.seleccionarLibro(libro.libro, libro.ejemplarId)
                                            }
                                    ) {
                                        Row(
                                            Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            PortadaRemota(
                                                url = libro.libro.portadaUrl,
                                                modifier = Modifier
                                                    .size(72.dp, 120.dp)
                                                    .clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.Crop
                                            )

                                            Spacer(Modifier.width(12.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(libro.libro.titulo)
                                                Text(libro.libro.autor)
                                                Text(
                                                    libro.libro.isbn,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = Color.Gray
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {

                                                    val colorEstado = remember(libro.libro.estado) {
                                                        when (libro.libro.estado.uppercase()) {
                                                            "NUEVO" -> Color(0xFF2E7D32)
                                                            "BUEN ESTADO" -> Color.Black
                                                            "USADO" -> Color(0xFFF9A825)
                                                            "DAÑADO" -> Color(0xFFD32F2F)
                                                            else -> Color.DarkGray
                                                        }
                                                    }
                                                    Text(
                                                        text = libro.libro.estado,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colorEstado
                                                    )
                                                    var estadoMenuOpen by remember(libro.ejemplarId) { mutableStateOf(false) }
                                                    Box {
                                                        IconButton(
                                                            onClick = { estadoMenuOpen = true },
                                                            modifier = Modifier.size(18.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Edit,
                                                                contentDescription = "Cambiar estado",
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                        }
                                                        DropdownMenu(
                                                            expanded = estadoMenuOpen,
                                                            onDismissRequest = { estadoMenuOpen = false }
                                                        ) {
                                                            listOf("Nuevo", "Buen estado", "Usado", "Dañado").forEach { opcion ->
                                                                DropdownMenuItem(
                                                                    text = { Text(opcion) },
                                                                    onClick = {
                                                                        estadoMenuOpen = false
                                                                        scope.launch {
                                                                            vm.actualizarEstadoLibro(libro.ejemplarId, opcion)
                                                                            vm.recargarLibros()
                                                                        }
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                if (p == null) {
                                                    AssistChip(
                                                        onClick = {},
                                                        label = { Text("Disponible") },
                                                        leadingIcon = {
                                                            Icon(
                                                                Icons.Default.Check,
                                                                null,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        },
                                                        colors = AssistChipDefaults.assistChipColors(
                                                            labelColor = Color(
                                                                0xFF2E7D32
                                                            )
                                                        )
                                                    )
                                                } else {
                                                    val vencido = isVencido(p)
                                                    Column {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(
                                                                imageVector = if (vencido) Icons.Default.Warning else Icons.Default.Person,
                                                                contentDescription = null,
                                                                tint = if (vencido) Color.Red else MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                            Spacer(Modifier.width(4.dp))
                                                            Text(
                                                                text = if (vencido) "¡VENCIDO!" else "Prestado",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (vencido) Color.Red else MaterialTheme.colorScheme.primary
                                                            )
                                                        }
                                                        Text(
                                                            "${p.alumno} (${p.curso})",
                                                            style = MaterialTheme.typography.bodySmall
                                                        )
                                                        Text(
                                                            "Vence: ${formatMillis(p.vencimientoAt)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = if (vencido) Color.Red else Color.Gray
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                                VerticalScrollbar(
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                    adapter = rememberScrollbarAdapter(scrollState)
                                )
                            }
                        }

                        else -> {
                            Text("Modo desconocido: $modo")  // ← FIX: contenido mínimo
                        }
                    }

                    if (mostrarInformacion) {
                        AboutDialog(onDismiss = { mostrarInformacion = false })
                    }
                }
            }
        }
    }
}

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Biblioteca Biblioteca Demo",
        icon = painterResource("logo_app.png"),
        state = rememberWindowState(width = 1200.dp, height = 800.dp)
    ) {
        App()
    }
}
