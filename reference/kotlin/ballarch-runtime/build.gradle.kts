plugins {
    kotlin("jvm")
    id("info.solidsoft.pitest")
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":ballarch-kernel"))
    testImplementation(project(":ballarch-testkit"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The exploration tests write sample conformance traces (tools/TRACE-FORMAT.md) here and,
// when python3 is available, check that tools/oracles.py agrees with Oracles.check on them.
val conformanceTraces = layout.buildDirectory.dir("conformance-traces")
val traceOracles = rootProject.file("../../tools/oracles.py")
val traceFormat = rootProject.file("../../tools/TRACE-FORMAT.md")

tasks.test {
    useJUnitPlatform()
    systemProperty("ballarch.traceDir", conformanceTraces.get().asFile.absolutePath)
    systemProperty("ballarch.oracles", traceOracles.absolutePath)
    inputs.files(traceOracles, traceFormat).withPropertyName("traceTools").withPathSensitivity(PathSensitivity.NAME_ONLY)
    outputs.dir(conformanceTraces).withPropertyName("conformanceTraces")
    doFirst { delete(conformanceTraces) }
}

// Optional mutation testing of the runtime: ./gradlew :ballarch-runtime:pitest
configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    pitestVersion.set("1.30.0")
    junit5PluginVersion.set("1.2.3")
    targetClasses.set(listOf("ballarch.runtime.*"))
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutators.set(listOf("DEFAULTS"))
    timeoutConstInMillis.set(10_000)
}
