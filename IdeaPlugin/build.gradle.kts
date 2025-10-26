import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.tasks.GetChangelogTask

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

plugins {
    alias(libs.plugins.changelog)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

group = "net.edwardday.kss"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

kotlin {
    jvmToolchain(21)
}

changelog {
    versionPrefix.set("")
    path.set(rootProject.file("CHANGELOG.md").canonicalPath)

}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = "252.*"
        }
        val projectVersion = project.version.toString()
        val changelog = tasks.getChangelog.flatMap(GetChangelogTask::changelog).map { changelog ->
            val item = if (projectVersion.endsWith("SNAPSHOT")) changelog.unreleasedItem!! else changelog.getLatest()
            changelog.renderItem(item, Changelog.OutputType.HTML)
        }
        changeNotes.set(changelog)
        version = projectVersion
    }
    buildSearchableOptions = false

    pluginVerification {
        ides {
            recommended()
        }
    }
    autoReload = false
}

dependencies {
    // Configure Gradle IntelliJ Plugin
    // Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html#setup
    intellijPlatform {
        // needed until 2025.3 can be targeted
        @Suppress("DEPRECATION")
        intellijIdeaCommunity("2025.2.2")

        bundledPlugins("org.jetbrains.kotlin")

        compileOnly(libs.kotlin.scripting.common)
        compileOnly(libs.kotlinx.coroutines)
    }
    implementation(project(":scripting-definition")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-jvm")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-script-runtime")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-dependencies-maven")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
    implementation(libs.kotlin.scripting.dependencies.maven.all) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-jvm")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-script-runtime")
    }
}
