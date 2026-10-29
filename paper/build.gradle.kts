plugins { java }

dependencies {
    implementation(project(":api"))
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("me.clip:placeholderapi:2.11.6")
}

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.jar {
    from(project(":api").extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()
        .getByName("main").output)
}

tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to project.version) }
    filesMatching("paper-plugin.yml") { expand("version" to project.version) }
}
