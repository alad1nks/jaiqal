package com.alad1nks.jaiqal.core.network

fun interface NetworkLogger {
    fun log(message: String)
}

object NoOpNetworkLogger : NetworkLogger {
    override fun log(message: String) = Unit
}
