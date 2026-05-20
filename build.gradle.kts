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
    signing
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
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
    explicitApi()
}

tasks.test {
    useJUnitPlatform()
}

java {
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
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("churnloop")
                        name.set("ChurnLoop")
                        email.set("dan@churnloop.com")
                        url.set("https://churnloop.com")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/ChurnLoop/sdk-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ChurnLoop/sdk-kotlin.git")
                    url.set("https://github.com/ChurnLoop/sdk-kotlin")
                }
            }
        }
    }

    repositories {
        // Maven Central via the Central Portal publishing API.
        // Set MAVEN_CENTRAL_USERNAME and MAVEN_CENTRAL_PASSWORD in
        // ~/.gradle/gradle.properties or as environment variables.
        maven {
            name = "MavenCentral"
            url = uri("https://central.sonatype.com/api/v1/publisher/upload")
            credentials {
                username = providers.gradleProperty("mavenCentralUsername")
                    .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                    .orNull
                password = providers.gradleProperty("mavenCentralPassword")
                    .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                    .orNull
            }
        }
    }
}

// GPG signing — required by Maven Central.
// Set signing.keyId, signing.password, signing.secretKeyRingFile
// in ~/.gradle/gradle.properties, or use the in-memory approach with
// signing.key (armored key) and signing.password env vars.
signing {
    val signingKey = providers.gradleProperty("signing.key")
        .orElse(providers.environmentVariable("SIGNING_KEY"))
        .orNull
    val signingPassword = providers.gradleProperty("signing.password")
        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
        .orNull

    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }

    sign(publishing.publications["library"])
}
