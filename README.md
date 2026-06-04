# 📚 Biblioteca Santa Teresa

> Aplicación de gestión bibliotecaria desarrollada y donada al **Colegio Concertado Santa Teresa**.  
> Un proyecto real, construido para resolver un problema real.
>
## Estado del proyecto

![Version](https://img.shields.io/badge/versión-2.0.1-brightgreen)
![Estado](https://img.shields.io/badge/estado-en%20uso%20activo-blue)

La aplicación lleva varios meses en uso en el centro educativo 
y se mantiene activamente. Esta versión incorpora mejoras 
sobre el modelo de datos de alumnos para dar soporte futuro 
a identificación por código de barras (lectura directa desde 
lector externo), además de correcciones y ajustes de usabilidad.

---

## ¿Qué es?

**Biblioteca Santa Teresa** es una aplicación de escritorio para la gestión integral de la biblioteca del colegio. Antes de su desarrollo, la biblioteca no contaba con ningún sistema informatizado. Este proyecto nació como una donación personal para darle solución a esa necesidad.

La aplicación permite gestionar de forma completa el día a día de una biblioteca escolar: el catálogo de libros, los préstamos activos, las devoluciones y los usuarios registrados.

---
## Capturas de pantalla

![Catálogo de libros](Captura%20de%20pantalla%20(1383).png)

![Gestión de alumnos](Captura%20de%20pantalla%20(1384).png)

---
## Funcionalidades

- 📖 **Catálogo de libros** — alta, baja y edición de títulos
- 👤 **Gestión de usuarios** — registro y consulta de alumnos y docentes
- 🔄 **Préstamos y devoluciones** — control del estado de cada ejemplar
- 🔍 **Búsqueda** — localización rápida de libros y usuarios
- 🗃️ **Historial** — registro de movimientos de la biblioteca

---

## Tecnologías utilizadas

| Tecnología | Uso |
|---|---|
| **Kotlin Multiplatform** | Base del proyecto, preparado para múltiples plataformas |
| **Compose Multiplatform** | Interfaz de usuario declarativa |
| **SQLDelight** | Persistencia de datos local |

> Plataforma actual: **Escritorio (JVM)**

---

## Contexto del proyecto

Este proyecto fue desarrollado de forma íntegra y donado gratuitamente al **Colegio Concertado Santa Teresa**. No es un ejercicio académico ni un proyecto de práctica: es una solución en uso real que resolvió la ausencia de informatización en su biblioteca.

Representa mi forma de entender el desarrollo de software: orientado a resolver problemas concretos de personas reales.

---

## Ejecutar la aplicación

```bash
# macOS / Linux
./gradlew :composeApp:run

# Windows
.\gradlew.bat :composeApp:run
```

---

## Autor

**K.Dev** · [github.com/MdVdiag](https://github.com/MdVdiag) · [mdvdiag.github.io](https://mdvdiag.github.io)
