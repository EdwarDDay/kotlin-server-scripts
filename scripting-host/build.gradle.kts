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

application {
    mainClass.set("net.edwardday.serverscript.scripthost.MainKt")
}
