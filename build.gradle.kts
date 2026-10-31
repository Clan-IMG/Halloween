plugins {
    id("java-library")
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

val paperVersion = "1.21.11-R0.1-SNAPSHOT"
// Lädt Paper zur Laufzeit selbst ("libraries" in der plugin.yml) - nicht ins Jar packen.
val hikariVersion = "7.1.0"
val mariadbVersion = "3.5.10"

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:$mariadbVersion")

    testImplementation("io.papermc.paper:paper-api:$paperVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    test {
        useJUnitPlatform()
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    processResources {
        val props = mapOf("version" to version, "hikari" to hikariVersion, "mariadb" to mariadbVersion)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
