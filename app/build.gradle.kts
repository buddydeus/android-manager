import java.net.URI

plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
fun quoted(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\""
val enrollmentKey=providers.environmentVariable("AMS_ENROLLMENT_KEY").orElse("")
android {
    namespace = "com.buddydeus.androidmanager"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.buddydeus.androidmanager"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String","AMS_SERVER_ORIGIN",quoted("https://ams.opt.work"))
        buildConfigField("String","AMS_ENROLLMENT_KEY",quoted(enrollmentKey.get()))
    }
    buildTypes {
        getByName("debug") {
            val origin=providers.environmentVariable("AMS_TEST_ORIGIN").orElse("https://ams.opt.work").get()
            val uri=URI(origin)
            require(uri.scheme=="https" && uri.rawUserInfo==null && (uri.path.isNullOrEmpty() || uri.path=="/") && uri.query==null && uri.fragment==null) { "测试服务地址须为HTTPS原点" }
            buildConfigField("String","AMS_SERVER_ORIGIN",quoted(origin.trimEnd('/')))
        }
    }
    buildFeatures { compose = true;buildConfig=true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
