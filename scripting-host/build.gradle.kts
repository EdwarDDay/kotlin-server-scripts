import org.gradle.jvm.component.internal.JvmSoftwareComponentInternal

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

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

group = "net.edwardday.serverscript"

dependencies {
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(project(":scripting-definition")) // the script definition module

    implementation(libs.okio)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.oshai.logging)
    runtimeOnly(libs.logback.logger)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

val createReleaseStartScript by tasks.registering {
    val startScriptsTask = tasks.named("startScripts")
    dependsOn(startScriptsTask)
    val outputDirectory = layout.buildDirectory.dir("dist/release/scripts")
    outputs.dir(outputDirectory)
    doLast {
        val directory = outputDirectory.get().asFile
        startScriptsTask.get().outputs.files.flatMap {
            if (it.isFile) listOf(it) else it.listFiles()?.asList().orEmpty()
        }.map { script ->
            val classpathLineStart: String
            val replaceOriginal: String
            val replaceNewValue: String
            if (script.name.endsWith(".bat")) {
                classpathLineStart = "set CLASSPATH="
                replaceOriginal = "%APP_HOME%\\lib\\"
                replaceNewValue = "%APP_HOME%\\kss_lib\\"
            } else {
                classpathLineStart = "CLASSPATH="
                replaceOriginal = "\$APP_HOME/lib/"
                replaceNewValue = "\$APP_HOME/kss_lib/"
            }
            File(directory, script.name).also { file ->
                script.bufferedReader().useLines { scriptLines ->
                    file.bufferedWriter().use { writer ->
                        scriptLines.forEach { line ->
                            val updatedLine = if (!line.startsWith(classpathLineStart)) {
                                line
                            } else {
                                line.replace(replaceOriginal, replaceNewValue)
                            }
                            writer.appendLine(updatedLine)
                        }
                    }
                }
            }
        }
    }
}

distributions {
    create("release") {
        contents {
            into("bin") {
                from(createReleaseStartScript)
                into("kss_lib") {
                    from(tasks.getByName("jar"))
                    from((components["java"] as JvmSoftwareComponentInternal).mainFeature.runtimeClasspathConfiguration)
                }
            }
            from("src/release")
        }
    }
}

tasks.named<Tar>("releaseDistTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

application {
    mainClass.set("net.edwardday.serverscript.scripthost.MainKt")
    applicationName = "kss"
    executableDir = ""
}
