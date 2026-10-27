dependencies {
    implementation(project(":ballarch-kernel"))
    // Only the composition root (package ...catalog.app) uses the runtime.
    implementation(project(":ballarch-runtime"))
    testImplementation(project(":ballarch-testkit"))
}
