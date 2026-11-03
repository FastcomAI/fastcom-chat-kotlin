import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.concurrent.Callable

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    `java-library`
    signing
    id("com.vanniktech.maven.publish") version "0.30.0"
}

// Java 8 bytecode, so the library runs on every Android version OkHttp 4 supports.
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.test {
    useJUnitPlatform()
}

// Maven Central (Central Portal). Credentials and the signing key come from
// ~/.gradle/gradle.properties or ORG_GRADLE_PROJECT_* env vars, never this repo.
mavenPublishing {
    coordinates("io.github.fastcomai", "fastcom-chat", "0.1.0")
    // Central requires a javadoc jar; an empty one is accepted.
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    pom {
        name.set("Fastcom Chat")
        description.set("Kotlin SDK for the Fastcom Mobile Chat API, for Android and JVM apps.")
        inceptionYear.set("2026")
        url.set("https://github.com/FastcomAI/fastcom-chat-kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("fastcomai")
                name.set("FastcomAI")
                url.set("https://github.com/FastcomAI")
            }
        }
        scm {
            url.set("https://github.com/FastcomAI/fastcom-chat-kotlin")
            connection.set("scm:git:https://github.com/FastcomAI/fastcom-chat-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/FastcomAI/fastcom-chat-kotlin.git")
        }
    }
}

// Signing is mandatory for a Maven Central upload and skipped otherwise, so
// `publishToMavenLocal` works on a machine without the release key.
signing {
    setRequired(Callable {
        gradle.taskGraph.allTasks.any { it.name.contains("MavenCentral") }
    })
}
