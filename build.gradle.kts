// Diese Datei sagt Gradle (dem Bau-Werkzeug), wie aus dem Quellcode die fertige Plugin-Datei (JAR) entsteht.

plugins {
    java
}

group = "de.coinshop"
version = "1.0.0"

repositories {
    mavenCentral()
    // Hier liegt die Paper-API (die "Werkzeugkiste" für Minecraft-Plugins)
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // compileOnly = Die API wird nur zum Schreiben/Übersetzen gebraucht.
    // Zur Laufzeit liefert der Server sie selbst mit.
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    // Paper 26.1 und neuer braucht Java 25
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.jar {
    // Dateiname der fertigen Datei: build/libs/CoinShop.jar
    archiveBaseName.set("CoinShop")
    archiveVersion.set("")
}
