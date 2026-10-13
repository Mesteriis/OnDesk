plugins { id("com.android.application") }

android {
    namespace = "app.ondesk.launcher"
    compileSdk = 37
    defaultConfig {
        minSdk = 36
        targetSdk = 37
        versionCode = 7
        versionName = "0.4.3"
    }
    buildFeatures { buildConfig = true; resValues = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
    flavorDimensions += "action"
    productFlavors {
        create("screenshot") {
            dimension = "action"
            applicationId = "app.ondesk.launcher.screenshot"
            resValue("string", "app_name", "OnDesk — Скриншот")
            buildConfigField("String", "TARGET_ACTIVITY", "\"app.ondesk.ScreenshotShortcutActivity\"")
        }
        create("camera") {
            dimension = "action"
            applicationId = "app.ondesk.launcher.camera"
            resValue("string", "app_name", "OnDesk — Камера")
            buildConfigField("String", "TARGET_ACTIVITY", "\"app.ondesk.CameraShortcutActivity\"")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

val prepareLicenseAssets by tasks.registering(Sync::class) {
    from(rootProject.file("../LICENSES")) { into("licenses") }
    from(rootProject.file("../LICENSE")) { into("licenses") }
    from(rootProject.file("../THIRD_PARTY_NOTICES.md")) { into("licenses") }
    into(layout.buildDirectory.dir("generated/licenseAssets"))
}
tasks.named("preBuild") { dependsOn(prepareLicenseAssets) }
