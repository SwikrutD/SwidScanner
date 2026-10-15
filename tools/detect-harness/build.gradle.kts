// Runs the app's page detection and filters (core/DocProcessing.kt) on a folder of
// images on the desktop JVM, using the openpnp OpenCV build. No Android needed.
//   ..\..\gradlew.bat -p tools\detect-harness run --args="samples out"
// Replay live frames recorded with the app's debug REC button (pulled into ./frames):
//   ..\..\gradlew.bat -p tools\detect-harness run --args="frames frames frames-out"
plugins { kotlin("jvm") version "2.1.20"; application }
repositories { mavenCentral() }
dependencies {
    implementation("org.openpnp:opencv:4.9.0-0")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(17) }
sourceSets { main { kotlin.srcDir("../../app/src/main/java/io/github/swikrutd/swidscanner/core") } }
application { mainClass.set("HarnessKt") }
