// Проверенная совместимая тройка (под JDK 21 в Termux/AndroidIDE):
//   Gradle  8.9   (задан в gradle-wrapper.properties)
//   AGP     8.5.2 (требует Gradle 8.7+, поддерживает сборку под JDK 21)
//   Kotlin  1.9.24
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
