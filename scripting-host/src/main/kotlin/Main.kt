package net.edwardday.serverscript.scripthost

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*

private const val CONFIG_FILE_NAME = "kss.properties"

suspend fun main() {
    // load default config
    val defaultConfig = try {
        Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream(CONFIG_FILE_NAME)
            ?.use<InputStream?, Properties> { stream ->
                Properties().apply {
                    load(stream)
                }
            }
            ?: error("Could not load default configuration")
    } catch (e: IOException) {
        error("Could not read default configuration because of ${e.localizedMessage}")
    }

    // load config file from working directory
    val workingDirectoryConfig = Properties(defaultConfig)
    try {
        File(CONFIG_FILE_NAME).takeIf(File::exists)?.bufferedReader()?.use(workingDirectoryConfig::load)
    } catch (e: IOException) {
        // Ignore for now, maybe log?
    }

    // load config from system properties
    val commandLineConfig = Properties(workingDirectoryConfig)
    workingDirectoryConfig.keys.forEach { key ->
        val keyAsString = key.toString()
        System.getProperty(keyAsString)?.also { value -> commandLineConfig[keyAsString] = value }
    }

    val socket = commandLineConfig.getProperty("socket.address") ?: "unix:/var/run/kss/kss.sock"
    val maxConnections = commandLineConfig.getProperty("connections.max")?.toIntOrNull() ?: 4

    readFromSocket(
        socket = socket,
        maxConnections = maxConnections,
    )
}