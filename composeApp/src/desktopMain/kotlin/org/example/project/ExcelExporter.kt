package org.example.project

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.awt.EventQueue
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter


fun exportarPrestamosAExcel(listaPrestamos: List<List<String>>) {
    // 1. Configurar la ventana para guardar el archivo
    val chooser = JFileChooser().apply {
        dialogTitle = "Guardar Informe de Préstamos"
        fileFilter = FileNameExtensionFilter("Libro de Excel (.xlsx)", "xlsx")
        selectedFile = File("Informe_Prestamos.xlsx")
    }

    // 2. Si el usuario elige una ruta
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val file = if (chooser.selectedFile.extension == "xlsx") chooser.selectedFile
        else File("${chooser.selectedFile.absolutePath}.xlsx")

        try {
            val workbook = XSSFWorkbook() // <--- Esto crea el archivo Excel
            val sheet = workbook.createSheet("Préstamos")

            // Crear la cabecera
            val header = sheet.createRow(0)
            val columnas = listOf("Libro", "Alumno / Estado Préstamo", "Fecha de Entrega", "Estado del Libro")

            columnas.forEachIndexed { i, texto -> header.createCell(i).setCellValue(texto) }

            // Llenar con los datos que le pasamos
            listaPrestamos.forEachIndexed { index, filaDatos ->
                val row = sheet.createRow(index + 1)
                filaDatos.forEachIndexed { colIndex, valor ->
                    row.createCell(colIndex).setCellValue(valor)
                }
            }

            // Guardar físicamente en el disco
            FileOutputStream(file).use { out ->
                workbook.write(out)
            }
            workbook.close()
            println("Excel generado con éxito en: ${file.absolutePath}")

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

/**
 * Abre un selector de archivo para importar alumnos desde CSV/XLSX.
 * Devuelve el archivo elegido o null si se cancela.
 */
fun elegirArchivoAlumnos(): File? {
    fun showChooser(): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Importar Alumnos (Excel/CSV)"
            fileFilter = FileNameExtensionFilter("Excel (.xlsx) o CSV (.csv)", "xlsx", "csv")
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    return try {
        if (SwingUtilities.isEventDispatchThread()) {
            showChooser()
        } else {
            var selected: File? = null
            EventQueue.invokeAndWait { selected = showChooser() }
            selected
        }
    } catch (_: Exception) {
        null
    }
}

fun elegirImagenPortada(): File? {
    fun showChooser(): File? {
        val chooser = JFileChooser().apply {
            dialogTitle = "Seleccionar imagen de portada"
            fileFilter = FileNameExtensionFilter("Imágenes (.jpg, .png)", "jpg", "jpeg", "png")
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    return try {
        if (SwingUtilities.isEventDispatchThread()) {
            showChooser()
        } else {
            var selected: File? = null
            EventQueue.invokeAndWait { selected = showChooser() }
            selected
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Lee una lista de nombres desde un archivo CSV o XLSX.
 * Regla: tomamos la primera columna de cada fila (ignorando vacías).
 * Si hay cabecera ("nombre", "alumno", etc.), la ignoramos.
 */
fun leerNombresAlumnosDesdeArchivo(file: File): List<Pair<String, String?>> {
    val ext = file.extension.lowercase()
    return try {
        when (ext) {
            "csv" -> leerNombresDesdeCsv(file)
            "xlsx" -> leerNombresDesdeXlsx(file)
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun leerNombresDesdeCsv(file: File): List<Pair<String, String?>> {
    if (!file.exists()) return emptyList()
    val lines = file.readLines()
    if (lines.isEmpty()) return emptyList()

    val rows = lines.asSequence()
        .map { it.trim() }.filter { it.isNotBlank() }
        .map { parseCsvLine(it) }.filter { it.isNotEmpty() }
        .toList()

    if (rows.isEmpty()) return emptyList()

    val header = rows.first().map { it.normalizarCabecera() }
    val idxNombre = header.indexOfFirst { it in setOf("nombre", "nombres") }
    val idxAp1 = header.indexOfFirst { it in setOf("apellido", "apellido1", "primerapellido", "apellidos") }
    val idxAp2 = header.indexOfFirst { it in setOf("apellido2", "segundoapellido") }
    val idxCodigo = header.indexOfFirst { it in setOf("codigo", "codigoalumno", "expediente", "id") }

    val hasHeader = idxNombre != -1 || idxAp1 != -1 || idxAp2 != -1
    val dataRows = if (hasHeader) rows.drop(1) else rows

    return dataRows.asSequence().mapNotNull { cols ->
        val nombre = construirNombreCompletoDesdeColumnas(
            cols, if (hasHeader) idxNombre else -1,
            if (hasHeader) idxAp1 else -1, if (hasHeader) idxAp2 else -1
        ) ?: return@mapNotNull null
        val codigo = if (idxCodigo >= 0) cols.getOrNull(idxCodigo)?.trim()?.ifBlank { null }
            ?.let { if (it.endsWith(".0")) it.dropLast(2) else it } else null
        nombre to codigo
    }.filter { it.first.isNotBlank() }.distinctBy { it.first }.toList()
}

private fun leerNombresDesdeXlsx(file: File): List<Pair<String, String?>> {
    if (!file.exists()) return emptyList()
    var result: List<Pair<String, String?>> = emptyList()
    FileInputStream(file).use { fis ->
        XSSFWorkbook(fis).use { workbook ->
            val sheet = workbook.getSheetAt(0) ?: return@use
            val rows = sheet.iterator().asSequence().map { row ->
                (0 until 12).mapNotNull { c -> row.getCell(c)?.toString()?.trim() }
                    .map { it.trim() }.dropLastWhile { v -> v.isBlank() }
            }.filter { it.any { v -> v.isNotBlank() } }.toList()

            if (rows.isEmpty()) return@use

            val header = rows.first().map { it.normalizarCabecera() }
            val idxNombre = header.indexOfFirst { it in setOf("nombre", "nombres") }
            val idxAp1 = header.indexOfFirst { it in setOf("apellido", "apellido1", "primerapellido", "apellidos") }
            val idxAp2 = header.indexOfFirst { it in setOf("apellido2", "segundoapellido") }
            val idxCodigo = header.indexOfFirst { it in setOf("codigo", "codigoalumno", "expediente", "id") }

            val hasHeader = idxNombre != -1 || idxAp1 != -1 || idxAp2 != -1
            val dataRows = if (hasHeader) rows.drop(1) else rows

            result = dataRows.asSequence().mapNotNull { cols ->
                val nombre = construirNombreCompletoDesdeColumnas(
                    cols, if (hasHeader) idxNombre else -1,
                    if (hasHeader) idxAp1 else -1, if (hasHeader) idxAp2 else -1
                ) ?: return@mapNotNull null
                val codigo = if (idxCodigo >= 0) cols.getOrNull(idxCodigo)?.trim()?.ifBlank { null } else null
                nombre to codigo
            }.filter { it.first.isNotBlank() }.distinctBy { it.first }.toList()
        }
    }
    return result
}

private fun construirNombreCompletoDesdeColumnas(
    cols: List<String>,
    idxNombre: Int,
    idxAp1: Int,
    idxAp2: Int
): String? {
    fun get(i: Int): String = cols.getOrNull(i)?.trim().orEmpty()

    // Caso 1: tenemos cabecera y por tanto índices fiables.
    if (idxNombre >= 0 || idxAp1 >= 0 || idxAp2 >= 0) {
        val nombre = if (idxNombre >= 0) get(idxNombre) else ""
        val ap1 = if (idxAp1 >= 0) get(idxAp1) else ""
        val ap2 = if (idxAp2 >= 0) get(idxAp2) else ""
        val full = listOf(ap1, ap2, nombre).joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return full.ifBlank { null }
    }

    // Caso 2 (sin cabecera): formato típico: Apellido1, Apellido2, Nombre, ...
    val c0 = get(0)
    val c1 = get(1)
    val c2 = get(2)
    val fullFrom3 = listOf(c2, c0, c1).joinToString(" ").replace(Regex("\\s+"), " ").trim()
    if (fullFrom3.isNotBlank()) return fullFrom3

    // Caso 3: si solo hay una columna, asumimos que ya viene "Nombre Apellidos".
    val one = c0.replace(Regex("\\s+"), " ").trim()
    return one.ifBlank { null }
}

private fun String.normalizarCabecera(): String =
    this
        .trim()
        .lowercase()
        .replace("á", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ú", "u")
        .replace("ü", "u")
        .replace("ñ", "n")
        .replace(Regex("[^a-z0-9]"), "")

private fun parseCsvLine(line: String): List<String> {
    // Parser básico que respeta comillas dobles.
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when (ch) {
            '"' -> {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            }
            ';', ',' -> {
                if (inQuotes) sb.append(ch)
                else {
                    out.add(sb.toString().trim())
                    sb.setLength(0)
                }
            }
            else -> sb.append(ch)
        }
        i++
    }
    out.add(sb.toString().trim())
    return out.map { it.trim().trim('"') }
}
