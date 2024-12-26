/*
 * Copyright 2024 Eduard Wolf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.edwardday.serverscript.scripthost

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*

private const val CONFIG_FILE_NAME = "kss.properties"

private fun loadProperties(): Properties {
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
    return commandLineConfig
}

suspend fun main() {
    val properties = loadProperties()

    val loggingFile = properties.getProperty("logging.logback.configurationFile").orEmpty()
    if (loggingFile.isNotEmpty() && File(loggingFile).exists()) {
        System.setProperty("logback.configurationFile", loggingFile)
    }

    val logger = KotlinLogging.logger {}

    val mavenDependenciesSettings = properties.getProperty("dependencies.maven.settingsFile").orEmpty()
    val mavenDependenciesHome = properties.getProperty("dependencies.maven.homeDirectory").orEmpty()
    if (mavenDependenciesSettings.isNotEmpty() && File(mavenDependenciesSettings).exists()) {
        if (mavenDependenciesHome.isNotEmpty()) {
            logger.warn { "'dependencies.maven.settingsFile' overwrites 'dependencies.maven.homeDirectory' setting." }
        }
        System.setProperty("org.apache.maven.user-settings", mavenDependenciesSettings)
    } else {
        val mavenDependenciesHomeDirectory = File(mavenDependenciesHome)
        if (mavenDependenciesHome.isNotEmpty() && mavenDependenciesHomeDirectory.isDirectory()) {
            if (mavenDependenciesHomeDirectory.canWrite()) {
                val settingsFile = File(mavenDependenciesHomeDirectory, "settings.xml")
                val repositoryDirectory = File(mavenDependenciesHomeDirectory, "repository")
                repositoryDirectory.mkdir()
                settingsFile.printWriter().use { writer ->
                    writer.println("""<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"""")
                    writer.println("""  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
                    writer.println("""  xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">""")
                    writer.println("""  <localRepository>${repositoryDirectory.absolutePath}</localRepository>""")
                    writer.println("""</settings>""")
                }
                System.setProperty("org.apache.maven.user-settings", settingsFile.absolutePath)
            } else {
                logger.warn { "User can't write in ${mavenDependenciesHomeDirectory.absolutePath}" }
            }
        }
    }
    val socket = properties.getProperty("socket.address") ?: "unix:/var/run/kss/kss.sock"
    val maxConnections = properties.getProperty("connections.max")?.toIntOrNull() ?: 4

    readFromSocket(
        socket = socket,
        maxConnections = maxConnections,
    )
}
