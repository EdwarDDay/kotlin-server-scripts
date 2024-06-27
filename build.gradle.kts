plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "net.edwardday.serverscript"

allprojects {
    repositories {
        mavenCentral()
    }
}
