plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "net.fuyumori.stellarss"
    compileSdk = 35
    defaultConfig {
        applicationId = "net.fuyumori.stellarss"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("$projectDir/schemas")
    buildFeatures { buildConfig = true }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.21.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
