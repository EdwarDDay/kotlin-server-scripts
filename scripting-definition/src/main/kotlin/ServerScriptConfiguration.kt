/*
 * Copyright 2025 Eduard Wolf
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

package net.edwardday.serverscript.scriptdefinition

import kotlinx.coroutines.runBlocking
import net.edwardday.serverscript.scriptdefinition.annotation.Import
import net.edwardday.serverscript.scriptdefinition.script.ServerScript
import java.io.File
import kotlin.script.experimental.api.RefineScriptCompilationConfigurationHandler
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCollectedData
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptConfigurationRefinementContext
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptSourceAnnotation
import kotlin.script.experimental.api.asDiagnostics
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.collectedAnnotations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.displayName
import kotlin.script.experimental.api.fileExtension
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.importScripts
import kotlin.script.experimental.api.onSuccess
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath

const val SERVER_SCRIPT_FILE_EXTENSION = "server.kts"
private const val SERVER_SCRIPT_DISPLAY_NAME = "KSS .server.kts"

internal class ServerScriptConfiguration : ScriptCompilationConfiguration(
    body = {
        displayName(SERVER_SCRIPT_DISPLAY_NAME)
        fileExtension(SERVER_SCRIPT_FILE_EXTENSION)
        defaultImports(DependsOn::class, Repository::class, Import::class)
        defaultImports("net.edwardday.serverscript.scriptdefinition.script.*")
        jvm {
            dependenciesFromClassContext(
                ServerScriptDefinition::class,
                "kotlin-scripting-dependencies",
                "scripting-definition",
            )
        }
        implicitReceivers(ServerScript::class)
        refineConfiguration {
            // the callback called when any of the listed file-level annotations are encountered in the compiled script
            // the processing is defined by the `handler`, that may return refined configuration depending on the annotations
            onAnnotations(
                DependsOn::class,
                Repository::class,
                handler = ServerScriptClasspathConfigurator(MavenDependenciesResolver())
            )
            onAnnotations(Import::class, handler = ServerScriptImportsConfigurator())
        }

    }
)

internal class ServerScriptImportsConfigurator : RefineScriptCompilationConfigurationHandler {
    override fun invoke(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)
            ?.takeIf(List<ScriptSourceAnnotation<*>>::isNotEmpty)
        val importAnnotations = annotations?.filter { it.annotation is Import }?.takeIf(List<*>::isNotEmpty)
            ?: return context.compilationConfiguration.asSuccess()
        val workingDir = (context.script as? FileBasedScriptSource)?.file?.absoluteFile?.parentFile
        val diagnostics = arrayListOf<ScriptDiagnostic>()
        val scripts = importAnnotations.mapNotNull { (annotation, location) ->
            val path = (annotation as Import).path.let {
                if (it.endsWith(".$SERVER_SCRIPT_FILE_EXTENSION")) it else "$it.$SERVER_SCRIPT_FILE_EXTENSION"
            }
            val absolutePathFile = File(path).takeIf(File::isAbsolute)
            when {
                absolutePathFile != null -> absolutePathFile.takeIf(File::isFile).also {
                    if (it == null) {
                        diagnostics += ScriptDiagnostic(
                            code = ScriptDiagnostic.unspecifiedError,
                            message = "cannot find import script file at $path",
                            severity = ScriptDiagnostic.Severity.WARNING,
                            locationWithId = location,
                        )
                    }
                }

                workingDir != null -> File(workingDir, path).takeIf(File::isFile).also {
                    if (it == null) {
                        diagnostics += ScriptDiagnostic(
                            code = ScriptDiagnostic.unspecifiedError,
                            message = "cannot find import script file at ${workingDir.path}/$path",
                            severity = ScriptDiagnostic.Severity.WARNING,
                            locationWithId = location,
                        )
                    }
                }

                else -> {
                    diagnostics += ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "cannot find import script file with relative path ($path) from a script with unknown location",
                        severity = ScriptDiagnostic.Severity.ERROR,
                        locationWithId = location,
                    )
                    null
                }
            }?.toScriptSource()
        }

        return if (diagnostics.isNotEmpty()) {
            ResultWithDiagnostics.Failure(diagnostics)
        } else {
            ScriptCompilationConfiguration(context.compilationConfiguration) {
                importScripts(scripts)
            }.asSuccess()
        }
    }

}

class ServerScriptClasspathConfigurator(mavenResolver: ExternalDependenciesResolver) :
    RefineScriptCompilationConfigurationHandler {
    private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), mavenResolver)

    override operator fun invoke(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> =
        processAnnotations(context)

    private fun processAnnotations(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        val diagnostics = arrayListOf<ScriptDiagnostic>()

        val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)
            ?.takeIf(List<*>::isNotEmpty)
            ?: return context.compilationConfiguration.asSuccess()

        val resolveResult = try {
            runBlocking {
                resolver.resolveFromScriptSourceAnnotations(
                    annotations.filter { it.annotation is DependsOn || it.annotation is Repository }
                )
            }
        } catch (e: Throwable) {
            ResultWithDiagnostics.Failure(
                *diagnostics.toTypedArray(),
                e.asDiagnostics(path = context.script.locationId)
            )
        }

        return resolveResult.onSuccess { resolvedClassPath ->
            ScriptCompilationConfiguration(context.compilationConfiguration) {
                updateClasspath(resolvedClassPath)
            }.asSuccess()
        }
    }
}
