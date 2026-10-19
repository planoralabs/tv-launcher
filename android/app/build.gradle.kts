import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chaves locais (fora do Git): android/local.properties → tmdb.key=SUA_CHAVE
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.planoralabs.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.planoralabs.tvlauncher"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2"
        buildConfigField("String", "TMDB_KEY", "\"${localProps.getProperty("tmdb.key", "")}\"")
    }
    buildFeatures { buildConfig = true }

    buildTypes {
        // Por enquanto usamos só a versão de depuração (assinada com a chave de debug local).
        debug { }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // A interface é o mesmo index.html do protótipo, copiado para dentro do APK.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/web"))
}

val copyWeb by tasks.registering(Copy::class) {
    from(rootDir.resolve("../tv-launcher")) { include("index.html") }
    into(layout.buildDirectory.dir("generated/web"))
}
tasks.named("preBuild") { dependsOn(copyWeb) }
