plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.satti.shinmaiothello"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.satti.shinmaiothello"
        minSdk = 29 // DAT の要件（Android 10+）に合わせた目安。公式ガイドで確認すること
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    // TODO(dat): Meta Wearables Device Access Toolkit の依存を追加する。
    // 座標（グループ/アーティファクト名）は公式ドキュメント
    // https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/
    // の現行版で確認すること（このスケルトンでは推測で書かない）。
    // 判明しているモジュール名の手がかり: mwdat-core / mwdat-camera / mwdat-display 相当。

    testImplementation("junit:junit:4.13.2")
}
