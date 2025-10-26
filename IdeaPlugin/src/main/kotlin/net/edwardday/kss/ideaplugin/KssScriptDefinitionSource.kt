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

package net.edwardday.kss.ideaplugin

import com.intellij.util.PathUtil
import kotlinx.coroutines.Job
import net.edwardday.serverscript.scriptdefinition.ServerScriptDefinition
import net.edwardday.serverscript.scriptdefinition.script.ServerScript
import org.jetbrains.kotlin.idea.base.plugin.artifacts.KotlinArtifacts
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionsSource
import java.io.File
import kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.configurationDependencies
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration

internal class KssScriptDefinitionSource : ScriptDefinitionsSource {
    override val definitions: Sequence<ScriptDefinition>
        get() {
            val definition = ScriptDefinition.FromTemplate(
                baseHostConfiguration = ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
                    val kotlinArtifacts = listOf(KotlinArtifacts.kotlinStdlib, KotlinArtifacts.kotlinScriptRuntime)
                    val kssArtifacts =
                        listOf(ServerScript::class, MavenDependenciesResolver::class, Job::class)
                            .map { File(PathUtil.getJarPathForClass(it.java)) }
                    val dependencies = (kssArtifacts + kotlinArtifacts).distinct()
                    configurationDependencies(listOf(JvmDependency(dependencies)))
                },
                contextClass = ServerScriptDefinition::class,
                template = ServerScriptDefinition::class,
            )
            definition.order = Int.MIN_VALUE
            return sequenceOf(definition)
        }
}
