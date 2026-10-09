plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":patcher"))
    implementation(libs.baksmali)
}

application {
    mainClass.set("nx.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx4g", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
