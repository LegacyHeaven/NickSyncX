plugins {
    java
}

allprojects {
    group = "dev.legacyheaven"
    version = "1.5.0"
}

subprojects {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    }
}

project(":velocity") {
    repositories {
        maven("https://nexus.velocitypowered.com/repository/maven-public/")
    }
}
