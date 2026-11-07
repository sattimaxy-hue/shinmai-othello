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
        // TODO(dat): DAT SDK の配布リポジトリは公式ガイドで確認して追加する
    }
}

rootProject.name = "shinmai-othello"
include(":app")
