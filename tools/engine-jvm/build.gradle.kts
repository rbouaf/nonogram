plugins { kotlin("jvm") version "2.4.0" }

// A JVM-only build of the nonogram engine package (app/src/main/java/app/materialnonogram/engine), so it
// can be tested and benchmarked without Android. Sources are the app's own, not copies. Bench.kt holds
// the calibration and timing modes (it may use the clock; the engine may not).
val app = file("../../app/src")
sourceSets {
    main { kotlin.srcDir("$app/main/java/app/materialnonogram/engine"); kotlin.srcDir("src/main/kotlin") }
    test { kotlin.srcDir("$app/test/java/app/materialnonogram/engine"); resources.srcDir("$app/test/resources") }
}
kotlin { jvmToolchain(21) }
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}
tasks.test {
    maxHeapSize = "2g"
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    System.getProperty("slow")?.let { systemProperty("slow", it) }
}
tasks.register<JavaExec>("run") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.materialnonogram.engine.tools.BenchKt")
    args = (System.getProperty("args") ?: "").split(" ").filter { it.isNotEmpty() }
    maxHeapSize = "4g"
}
// The jar d8 dexes for the phone/emulator run (tools/engine-jvm/device.sh).
tasks.register<Jar>("benchJar") {
    archiveFileName.set("bench.jar")
    from(sourceSets["main"].output)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/**")
}
