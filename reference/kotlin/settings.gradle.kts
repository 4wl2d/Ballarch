rootProject.name = "ballarch-reference"

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// Library
include(":ballarch-kernel", ":ballarch-runtime", ":ballarch-testkit", ":ballarch-archrules")

// Examples and executable documentation
include(
    ":examples:order-draft",
    ":examples:tutorial",
    ":examples:trip",
    ":examples:itinerary",
    ":examples:catalog",
    ":examples:place-order",
    ":examples:shop:payments-api",
    ":examples:shop:payments",
    ":examples:shop:payments-http",
    ":examples:shop:inventory-api",
    ":examples:shop:inventory",
    ":examples:shop:orders-api",
    ":examples:shop:orders",
    ":examples:shop:checkout",
    ":examples:shop:app",
)
