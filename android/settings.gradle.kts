pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        google()
        mavenCentral()
        // DAT SDK 1.0.1 は Maven Central（com.meta.wearable）で配布。追加リポジトリ不要。
    }
}

rootProject.name = "shinmai-othello"
include(":app")
