plugins {
    // AGP 8.5.2: compileSdk 35 を正式サポートする最初の安定版系列
    // AGP 8.2.x は compileSdk 34 まで → 35 にすると lint エラー/ビルド警告が出る
    id("com.android.application") version "8.5.2" apply false
    // Kotlin 2.0.0: Compose Compiler が Kotlin プラグインに統合された最初の安定版
    // kotlinCompilerExtensionVersion の手動指定が不要になる
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    // Kotlin 2.0.0 で Compose を使う場合に必要な Compose Compiler Gradle plugin
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
