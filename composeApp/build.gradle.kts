import org.jetbrains.compose.desktop.application.dsl.TargetFormat


plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.1.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("app.cash.sqldelight") version "2.0.2"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.serialization")
}





kotlin {
      jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3) // Usa material3 para que no de error
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)


                // Librerías escritas directamente para que no fallen:
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                implementation("org.apache.poi:poi-ooxml:5.2.5")
                implementation("io.ktor:ktor-client-core:2.3.7")
                implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
                implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
                implementation("io.ktor:ktor-client-cio:2.3.7")
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.2")

            }
        }

        val desktopMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
            }
        }
    }
}


compose.desktop {
    application {
        mainClass = "org.example.project.MainKt"


        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "BibliotecaDemo"
            packageVersion = "2.0.1"

            modules("java.sql", "java.naming", "java.security.jgss")

            windows {
                iconFile.set(project.file("src/desktopMain/resources/icono_v6.ico"))
                menuGroup = "Biblioteca Demo"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.bibliotecademo.database")
        }
    }
}
