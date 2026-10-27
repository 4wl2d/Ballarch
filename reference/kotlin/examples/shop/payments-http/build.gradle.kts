dependencies {
    // An adapter module: it implements the payments feature's provider ports over HTTP.
    implementation(project(":examples:shop:payments"))
    implementation(project(":ballarch-runtime"))
    testImplementation(project(":ballarch-testkit"))
}

tasks.test {
    systemProperty("ballarch.results", layout.buildDirectory.dir("reports/conformance").get().asFile.absolutePath)
}
