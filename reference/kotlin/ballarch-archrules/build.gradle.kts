plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    explicitApi()
}

dependencies {
    api(project(":ballarch-kernel"))
    api("com.tngtech.archunit:archunit:1.4.1")
    implementation(kotlin("reflect"))
    testImplementation(project(":ballarch-runtime"))
    // The rules are also run against the real examples.
    testImplementation(project(":examples:order-draft"))
    testImplementation(project(":examples:tutorial"))
    testImplementation(project(":examples:place-order"))
    testImplementation(project(":examples:catalog"))
    testImplementation(project(":examples:trip"))
    testImplementation(project(":examples:itinerary"))
    testImplementation(project(":examples:shop:checkout"))
    testImplementation(project(":examples:shop:payments"))
    testImplementation(project(":examples:shop:inventory"))
    testImplementation(project(":examples:shop:orders"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("ballarch.results", layout.buildDirectory.dir("reports/conformance").get().asFile.absolutePath)
}
