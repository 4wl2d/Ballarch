dependencies {
    implementation(project(":ballarch-kernel"))
    // Only the binding in app/PlaceOrderApp.kt uses the runtime.
    implementation(project(":ballarch-runtime"))
    testImplementation(project(":ballarch-testkit"))
}
