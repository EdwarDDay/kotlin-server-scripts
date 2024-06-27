package net.edwardday.serverscript.scriptdefinition

import kotlinx.coroutines.runBlocking
import net.edwardday.serverscript.scriptdefinition.script.ServerScript
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.RefineScriptCompilationConfigurationHandler
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCollectedData
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptConfigurationRefinementContext
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.asDiagnostics
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.collectedAnnotations
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.onSuccess
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.dependencies.resolveFromScriptSourceAnnotations
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath


const val SERVER_SCRIPT_FILE_EXTENSION = "server.kts"

@KotlinScript(
    fileExtension = SERVER_SCRIPT_FILE_EXTENSION,
    compilationConfiguration = ServerScriptConfiguration::class,
)
abstract class ServerScriptDefinition

class ServerScriptConfiguration : ScriptCompilationConfiguration(
    body = {
        defaultImports(DependsOn::class, Repository::class)
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
            onAnnotations(DependsOn::class, Repository::class, handler = ServerScriptConfigurator())
        }

    }
)

class ServerScriptConfigurator : RefineScriptCompilationConfigurationHandler {
    private val resolver = CompoundDependenciesResolver(FileSystemDependenciesResolver(), MavenDependenciesResolver())

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
