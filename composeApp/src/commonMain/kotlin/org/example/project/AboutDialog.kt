package org.example.project

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Biblioteca Escolar Biblioteca Demo de Jesús",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column {
                Text("Versión del Software: 2.0.1", fontWeight = FontWeight.SemiBold)
                Text("Desarrollado por: Avexia-Manuel Del Valle")

                Spacer(modifier = Modifier.height(16.dp))

                Text("Información Técnica:", style = MaterialTheme.typography.titleSmall)
                Divider(modifier = Modifier.padding(vertical = 4.dp))
                Text("• Motor de Base de Datos: SQLite via JDBC", style = MaterialTheme.typography.bodySmall)
                Text("• Interfaz: Jetpack Compose for Desktop", style = MaterialTheme.typography.bodySmall)
                Text("• Lenguaje: Kotlin 2.0", style = MaterialTheme.typography.bodySmall)

                Spacer(modifier = Modifier.height(16.dp))

                Text("Aviso de Protección de Datos:", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Esta aplicación cumple con la privacidad local. Los datos de los libros y usuarios " +
                            "se almacenan exclusivamente en este equipo (Carpeta AppData/Local). " +
                            "No se envían datos a servidores externos.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar", fontWeight = FontWeight.Bold)
            }
        }
    )
}
