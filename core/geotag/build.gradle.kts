import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // XmlPullParser は Android では標準で入っているので、JVM 側はコンパイルとテストだけ kxml2 を使う
    compileOnly(libs.kxml2)

    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
}
