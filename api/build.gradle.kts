plugins {
    `java-library`
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.jar {
    archiveBaseName.set("NickSyncX-API")
}