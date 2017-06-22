import build.*

import codegen.GenerateConfigurationExtensions
import codegen.GenerateKotlinDependencyExtensions

plugins {
    java // so we can benefit from the `java` accessor below
}

repositories {
    mavenLocal()
    maven {
        setUrl("https://dl.bintray.com/kotlin/kotlinx/")
    }
}

base {
    archivesBaseName = "gradle-kotlin-dsl"
}

dependencies {
    compileOnly(gradleApi())

    compile(project(":compiler-plugin"))
    compile(project(":tooling-models"))
    compile(kotlin2("stdlib"))
    compile(kotlin2("reflect"))
    compile(kotlin2("compiler-embeddable"))
    compile("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:0.16")

    testCompile(project(":test-fixtures"))
}


// --- Enable automatic generation of API extensions -------------------
val apiExtensionsOutputDir = file("src/generated/kotlin")

java.sourceSets["main"].kotlin {
    srcDir(apiExtensionsOutputDir)
}

val generateConfigurationExtensions by task<GenerateConfigurationExtensions> {
    outputFile = File(apiExtensionsOutputDir, "org/gradle/kotlin/dsl/ConfigurationsExtensions.kt")
}

val generateKotlinDependencyExtensions by task<GenerateKotlinDependencyExtensions> {
    val pluginsCurrentVersion: String by rootProject.extra
    outputFile = File(apiExtensionsOutputDir, "org/gradle/kotlin/dsl/KotlinDependencyExtensions.kt")
    embeddedKotlinVersion = kotlinVersion
    kotlinDslPluginsVersion = pluginsCurrentVersion
    kotlinDslRepository = kotlinRepo
}

val generateExtensions by tasks.creating {
    dependsOn(generateConfigurationExtensions)
    dependsOn(generateKotlinDependencyExtensions)
}

val compileKotlin by tasks
compileKotlin.dependsOn(generateExtensions)

val clean: Delete by tasks
clean.delete(apiExtensionsOutputDir)


// -- Testing ----------------------------------------------------------
val prepareIntegrationTestFixtures by rootProject.tasks
val customInstallation by rootProject.tasks
tasks {
    "test" {
        dependsOn(prepareIntegrationTestFixtures)
        dependsOn(customInstallation)
    }
}

withParallelTests()

// --- Utility functions -----------------------------------------------
fun kotlin2(module: String) = "org.jetbrains.kotlin:kotlin-$module:$kotlinVersion".also {
    println(it)
}

inline
fun <reified T : Task> task(noinline configuration: T.() -> Unit) = tasks.creating(T::class, configuration)

