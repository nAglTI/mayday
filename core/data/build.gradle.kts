import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "org.debs.mayday.core.data"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    providers.gradleProperty("mayday.test.maxHeap")
        .orElse(providers.environmentVariable("MAYDAY_TEST_MAX_HEAP"))
        .orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { maxHeapSize = it }
}

dependencies {
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.apk.parser)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.dexlib2)
    implementation(libs.hilt.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.snakeyaml)

    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
