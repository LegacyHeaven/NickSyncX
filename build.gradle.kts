plugins {
    java
}

allprojects {
    group = "dev.legacyheaven"
    version = "1.5.0"

    repositories {
        mavenCentral()
        maven("https://repo.purpurmc.org/snapshots/")
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
