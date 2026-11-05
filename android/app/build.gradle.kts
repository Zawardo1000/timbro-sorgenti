import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

/**
 * Versione derivata da git, come in treni: il conteggio dei commit fa da versionCode (monotono), lo SHA dice quale
 * build si ha in mano, con `-dirty` se in android/ ci sono modifiche non committate. providers.exec e non
 * ProcessBuilder: non rompe la configuration cache.
 *
 * Senza git (fuori dal PATH del daemon di Android Studio) o con un clone shallow il conteggio non vale: versionCode
 * 1 è l'ultima risorsa e Android rifiuterebbe l'installazione sopra una versione già presente, quindi lo si dice
 * nel log di Gradle invece di lasciarlo scoprire al telefono.
 */
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", "-C", projectDir.absolutePath, *args) }.standardOutput.asText.get().trim()
}.getOrNull()

// Nel repository pubblico (timbro-sorgenti) la storia è un'altra: la CI passa numero e commit del repository di
// sviluppo (file VERSIONE) con -Ptimbro.versionCode e -Ptimbro.gitSha, così l'app pubblicata ha la stessa versione
val versioneImposta: Int? = providers.gradleProperty("timbro.versionCode").orNull?.toIntOrNull()
val shaImposto: String? = providers.gradleProperty("timbro.gitSha").orNull?.ifBlank { null }

val gitCommitCount: Int = versioneImposta ?: run {
    val conteggio = git("rev-list", "--count", "HEAD")?.toIntOrNull()
    when {
        conteggio == null -> {
            logger.warn("Timbro: git non disponibile, versionCode = 1 (l'installazione sopra una versione più nuova fallirà)")
            1
        }
        git("rev-parse", "--is-shallow-repository") == "true" -> {
            logger.warn("Timbro: clone shallow, versionCode = $conteggio non confrontabile con le build complete")
            conteggio
        }
        else -> conteggio
    }
}
val gitSha: String = shaImposto ?: run {
    val sha = git("rev-parse", "--short", "HEAD")?.ifBlank { null } ?: "sconosciuto"
    // solo android/: le modifiche al backend non cambiano l'APK
    val sporco = git("status", "--porcelain", "--", rootDir.absolutePath)?.isNotEmpty() == true
    if (sporco) "$sha-dirty" else sha
}

/**
 * Indirizzi del server per la release: non stanno nei sorgenti (pubblici). Sul PC in android/server.properties (ignorato
 * da git: apiBase=https://..., enrollBase=https://...), nella CI dalle proprietà -Ptimbro.apiBase / -Ptimbro.enrollBase
 * prese dai segreti. Senza, la release non si costruisce (bundleRelease si ferma con un messaggio).
 */
val serverLocale: Properties? = rootProject.file("server.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
fun indirizzo(chiave: String): String? =
    providers.gradleProperty("timbro.$chiave").orNull?.ifBlank { null } ?: serverLocale?.getProperty(chiave)?.ifBlank { null }
val apiBase = indirizzo("apiBase")
val enrollBase = indirizzo("enrollBase")

/** Chiave di caricamento per Google Play: android/keystore.properties (ignorato da git), se c'è. */
val firmaCaricamento: Properties? = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "it.zawardo.timbro"
    compileSdk = 37

    signingConfigs {
        firmaCaricamento?.let { p ->
            create("caricamento") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "it.zawardo.timbro"
        minSdk = 29
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "0.1.$gitCommitCount"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    buildTypes {
        /*
         * Indirizzi del backend (docs/PROGETTO.md §22). In debug il backend sul PC visto dall'emulatore, in chiaro e
         * senza mTLS (network security config di debug, solo verso 10.0.2.2); in release l'API con mTLS e il nome
         * pubblico solo per /api/v1/enroll, che non chiede il certificato client (il telefono non ce l'ha ancora).
         */
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "API_BASE", "\"http://10.0.2.2:8012\"")
            buildConfigField("String", "ENROLL_BASE", "\"http://10.0.2.2:8012\"")
            buildConfigField("boolean", "MTLS", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "API_BASE", "\"${apiBase ?: "https://server.non.configurato.invalid"}\"")
            buildConfigField("String", "ENROLL_BASE", "\"${enrollBase ?: "https://server.non.configurato.invalid"}\"")
            buildConfigField("boolean", "MTLS", "true")
            // con keystore.properties (chiave di caricamento per Google Play, fuori dal repo) la release si firma per il
            // caricamento: ai telefoni arriva firmata da Google (Play App Signing). Senza, si prova con la chiave debug
            signingConfig = if (firmaCaricamento != null) signingConfigs.getByName("caricamento") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**")
    }

    // AGP 9: il supporto Kotlin è integrato, il blocco kotlin{} sta dentro android{}
    kotlin {
        jvmToolchain(17)
    }
}

ksp {
    // schema versionato della coda locale: le migrazioni future non si scrivono alla cieca
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.vm)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.video)
    implementation(libs.camera.view)
    implementation(libs.work.runtime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

/*
 * L'app bundle da caricare su Google Play finisce in android/dist/ (fuori da git) con il nome della versione, e resta
 * solo l'ultimo: Sync cancella quelli vecchi.
 */
val distribuisciBundle = tasks.register<Sync>("distribuisciBundle") {
    from(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    val nome = "timbro-0.1.$gitCommitCount.aab"  // calcolato qui: la lambda non deve tirarsi dietro lo script
    rename { nome }
}
val indirizziMancanti = apiBase == null || enrollBase == null
tasks.matching { it.name == "bundleRelease" || it.name == "assembleRelease" }.configureEach {
    // copia locale: un valore dello script dentro doFirst si porta dietro lo script, che la configuration cache rifiuta
    val mancanti = indirizziMancanti
    doFirst {
        if (mancanti) {
            throw GradleException("indirizzi del server mancanti: android/server.properties (apiBase, enrollBase) o -Ptimbro.apiBase/-Ptimbro.enrollBase")
        }
    }
}
tasks.matching { it.name == "bundleRelease" }.configureEach { finalizedBy(distribuisciBundle) }
