plugins { java }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    compileOnly("org.purpurmc.purpur:purpur-api:26.2.build.2621-stable")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
