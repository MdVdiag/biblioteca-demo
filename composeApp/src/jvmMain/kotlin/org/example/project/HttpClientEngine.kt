package org.example.project

import io.ktor.client.*
import io.ktor.client.engine.cio.*

actual fun createHttpClient(config: HttpClientConfig<*>.() -> Unit): HttpClient {
    return HttpClient(CIO) {
        config(this)
    }
}
