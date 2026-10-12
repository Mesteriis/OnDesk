plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "app.ondesk"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.ondesk"
        minSdk = 36
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/qaAssets").get().asFile)
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../LICENSES")) { into("licenses") }
    from(rootProject.file("../LICENSE")) { into("licenses") }
    from(rootProject.file("../THIRD_PARTY_NOTICES.md")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}
tasks.named("preBuild") { dependsOn(prepareLicenseAssets) }
