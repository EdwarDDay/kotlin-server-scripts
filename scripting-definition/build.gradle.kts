plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "net.edwardday.serverscript"

dependencies {
    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.dependencies)
    implementation(libs.kotlin.scripting.dependencies.maven)
    // coroutines dependency is required for this particular definition
    implementation(libs.kotlinx.coroutines)
}

kotlin {
    jvmToolchain(17)
}
