plugins {
    id("java-library")
    id("com.gradleup.shadow") version "8.3.5"
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    compileOnly("org.spigotmc:spigot-api:1.20.1-R0.1-SNAPSHOT")
    compileOnly("dev.gotiger:GTDonationCore:0.1.0")
    implementation("io.github.r2turntrue:chzzk4j:0.1.4")
    implementation("org.json:json:20090211")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
}

tasks {
    jar {
        enabled = false
    }
    shadowJar {
        archiveFileName = "GTDonation-Chzzk-${version}.jar"
    }
    build {
        dependsOn(shadowJar)
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    processResources {
        val props = mapOf("version" to version, "description" to project.description)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
