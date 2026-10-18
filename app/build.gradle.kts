plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.foodlog.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.foodlog.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("projectDir", projectDir.absolutePath)
            listOf("http.proxyHost","http.proxyPort","https.proxyHost","https.proxyPort").forEach { key ->
                System.getProperty(key)?.let { value -> it.systemProperty(key,value) }
            }
        }
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
val prepareLegacyFixture by tasks.registering(Copy::class) {
    from("$projectDir/../deliverables/个人旧记录_导入.json")
    into(layout.buildDirectory.dir("generated/androidTestAssets"))
    rename { "legacy_personal.json" }
}
android.sourceSets.getByName("androidTest").assets.srcDir(prepareLegacyFixture)
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.serialization)
    implementation(libs.datastore)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.test.ext)
    testImplementation(libs.compose.test)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.espresso.intents)
}
