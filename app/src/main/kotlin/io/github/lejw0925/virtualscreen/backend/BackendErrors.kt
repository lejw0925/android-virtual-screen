package io.github.lejw0925.virtualscreen.backend

class BackendException(val code: String, override val message: String = code) : Exception(message)

fun unsupportedBackend(kind: BackendKind, detail: String): Nothing =
    throw BackendException("${kind.wireName.uppercase()}_UNAVAILABLE", detail)
