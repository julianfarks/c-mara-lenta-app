plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    // La API Java de OpenCV es idéntica en el jar de escritorio y en el AAR de Android.
    // Aquí solo se compila contra ella (y se prueba en la JVM); en la app la aporta org.opencv:opencv.
    compileOnly("org.openpnp:opencv:4.9.0-0")

    testImplementation("org.openpnp:opencv:4.9.0-0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
