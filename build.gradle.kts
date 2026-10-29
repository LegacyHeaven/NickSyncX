plugins {
    java
}

allprojects {
    group = "dev.legacyheaven"
    version = "1.5.0"

    repositories {
        mavenCentral()
        maven("https://repo1.maven.org/maven2/")
        maven("https://repo.maven.apache.org/maven2/")
        maven("https://libraries.minecraft.net/")
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
        maven("https://repo.extendedclip.com/content/repositories/2.0/")
        maven("https://nexus.velocitypowered.com/repository/maven-public/")
        maven("https://repo.velocitypowered.com/repository/maven-public/")
        maven("https://repo.velocitypowered.com/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}
