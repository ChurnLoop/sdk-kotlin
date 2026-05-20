// Official Kotlin SDK for the ChurnLoop analytics + intervention platform.
//
// Pure JVM Kotlin library — works on Android (API 24+) and any
// server-side JVM environment. We do NOT use the Android-specific
// Gradle plugin so the SDK has no Android Gradle Plugin runtime
// dependency and consumers can use it in non-Android JVM apps
// (Ktor servers, Spring services, etc.) too.
//
// Zero runtime dependencies beyond the Kotlin stdlib + Kotlinx
// coroutines (used for non-blocking transport). HTTP via the JDK's
// built-in `HttpURLConnection` keeps the dependency surface small;
// migrating to OkHttp would add ~700 KB to the consumer's app.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
}

group = "io.churnloop"
version = "0.2.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Emit JDK 11 bytecode so Android (API 24+) and older
        // server-side JVMs can consume the SDK — matches the Java
        // target below. Required since Gradle 9 enforces Kotlin
        // and Java target consistency.
        jvmTarget.set(JvmTarget.JVM_11)
        // Strict null + explicit API mode (members must declare visibility)
        // — matches the SDK plan's "lock the surface deliberately" stance.
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
    explicitApi()
}

tasks.test {
    useJUnitPlatform()
}

java {
    // Target JDK 11 bytecode so the SDK works on Android (which
    // targets a Java 8/11 subset depending on toolchain) and on
    // older server-side JVMs.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])

            pom {
                name.set("ChurnLoop SDK")
                description.set("Official Kotlin SDK for the ChurnLoop analytics + intervention platform.")
                url.set("https://churnloop.com")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}
