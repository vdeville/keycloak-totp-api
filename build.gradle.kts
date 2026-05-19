import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "id.medihause"

// Version SPI Keycloak ciblée. Lue depuis gradle.properties (`keycloakVersion`),
// surchargable en CLI : `./gradlew shadowJar -PkeycloakVersion=26.6.0`.
val keycloakVersion: String by project
val keycloakMajor: String = keycloakVersion.substringBefore('.')
version = "1.2.0-kc$keycloakMajor"

repositories { mavenCentral() }

dependencies {
    // Fourni par Keycloak au runtime -> compileOnly
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")

    // Keycloak embarque déjà Jackson -> compileOnly (évite conflits de version)
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    compileOnly("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")

    // Kotlin, Keycloak ne l'embarque pas -> à inclure dans ton jar final
    implementation(kotlin("stdlib"))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Aligne le comportement des annotations sur paramètres data class avec
        // la future cible par défaut (param + property). Cf. KT-73255.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

tasks {
    val shadowJar by existing(ShadowJar::class) {
        // on n'embarque que Kotlin stdlib (et éventuellement kotlin-reflect si tu en as besoin)
        dependencies {
            include(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
        }
        // Nom fixe attendu par la CI (.github/workflows/build-release.yml)
        archiveFileName.set("keycloak-totp-api.jar")
    }

    shadowJar { dependsOn("classes") }

    // On ne déploie que le shadow jar dans Keycloak — le jar Kotlin non-shadowé
    // produit par `:jar` (~30 KB) est inutile et pollue build/libs/.
    jar { enabled = false }
}
