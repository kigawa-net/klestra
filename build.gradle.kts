import org.gradle.api.tasks.wrapper.Wrapper
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.kigawa"
version = "1.1.0"

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val jvmMain = kotlin.targets.getByName<KotlinJvmTarget>("jvm").compilations.getByName("main")
tasks.register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("klestra-jvm")
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "dev.kigawa.klestra.K8sMainKt")
    }
    from(jvmMain.output.allOutputs)
    configurations = listOf(project.configurations.getByName("jvmRuntimeClasspath"))
    dependencies {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
    mergeServiceFiles()
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Run an in-memory three-node Embedded Quorum demo."
    dependsOn(jvmMain.compileTaskProvider)
    classpath = jvmMain.output.allOutputs + jvmMain.runtimeDependencyFiles
    mainClass.set("dev.kigawa.klestra.MainKt")
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(17))
    })
}

tasks.wrapper {
    gradleVersion = "8.14"
    distributionType = Wrapper.DistributionType.BIN
}
