import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Logica pura, senza Android: manifest della timbratura (i byte firmati), modelli dell'API del backend, filtro
 * delle reti Wi-Fi. Si prova sulla JVM, senza emulatore, e il compilatore impedisce che ci entri codice Android.
 */
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.bcpkix)  // verifica indipendente del CSR (Csr.kt)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
