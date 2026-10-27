dependencies {
    // A Flow depends on the public APIs of its participants, never on their implementations.
    api(project(":examples:shop:payments-api"))
    api(project(":examples:shop:inventory-api"))
    api(project(":examples:shop:orders-api"))
    testImplementation(project(":ballarch-testkit"))
    testImplementation(project(":examples:shop:payments"))
    testImplementation(project(":examples:shop:inventory"))
    testImplementation(project(":examples:shop:orders"))
}
