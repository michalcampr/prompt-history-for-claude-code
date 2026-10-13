plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "cz.campr"
version = "0.3.1"

// -PplatformPath=/path/to/ide builds against a locally installed IDE (no download);
// it can live in ~/.gradle/gradle.properties. Without it, PhpStorm platformVersion is downloaded.
val platformPath = providers.gradleProperty("platformPath").orNull

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (platformPath != null) local(platformPath) else phpstorm(providers.gradleProperty("platformVersion").get())
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
