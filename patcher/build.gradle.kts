import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.dexlib2)
    api(libs.arsclib)
    api(libs.apksig)
    implementation(libs.bcpkix)
}

// region Extension: Java code that runs inside the patched app, shipped as a dex resource.

val sdkDir: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(props::load)
    props.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Android SDK not found: set sdk.dir in local.properties or ANDROID_HOME")
}

/** Newest installed SDK component directory (e.g. platforms/android-36) that contains [file]. */
fun newestSdkFile(dir: String, file: String): File =
    File(sdkDir, dir).listFiles().orEmpty()
        .filter { File(it, file).exists() }
        .maxByOrNull { it.name.filter(Char::isDigit).padEnd(8, '0').toLong() }
        ?.let { File(it, file) }
        ?: error("$file not found in $sdkDir/$dir")

val androidJar = newestSdkFile("platforms", "android.jar")
val d8 = newestSdkFile("build-tools", "d8")
val extensionClasses = layout.buildDirectory.dir("extension/classes")
val extensionResources = layout.buildDirectory.dir("extension/resources")

val compileExtension by tasks.registering(JavaCompile::class) {
    source = fileTree(rootProject.file("extension/src/main/java"))
    classpath = files(androidJar)
    destinationDirectory.set(extensionClasses)
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
}

val dexExtension by tasks.registering(Exec::class) {
    dependsOn(compileExtension)
    val outDir = extensionResources.map { it.dir("nx") }
    inputs.dir(extensionClasses)
    outputs.dir(extensionResources)
    doFirst {
        outDir.get().asFile.mkdirs()
        val classFiles = extensionClasses.get().asFile.walk().filter { it.extension == "class" }.map { it.path }.toList()
        commandLine(
            listOf(d8.path, "--release", "--min-api", "28", "--lib", androidJar.path, "--output", outDir.get().asFile.path) + classFiles,
        )
    }
    doLast {
        val dir = outDir.get().asFile
        // Not named .dex, which R8 would mistake for app code when shrinking the Android app.
        File(dir, "classes.dex").renameTo(File(dir, "extension.bin"))
    }
}

sourceSets.main {
    resources.srcDir(extensionResources)
}
tasks.processResources { dependsOn(dexExtension) }

// endregion
