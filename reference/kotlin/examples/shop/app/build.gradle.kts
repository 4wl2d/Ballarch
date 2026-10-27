dependencies {
    // The composition root is the one module that knows every implementation.
    api(project(":ballarch-runtime"))
    api(project(":examples:shop:payments"))
    api(project(":examples:shop:inventory"))
    api(project(":examples:shop:orders"))
    api(project(":examples:shop:checkout"))
    testImplementation(project(":ballarch-testkit"))
}
