package ballarch.examples.shop.inventory.api

import ballarch.kernel.BallPort

/** Public contract of the inventory feature. One instance per SKU. */
sealed interface InventoryRequest {
    /** Reserve stock for a checkout. Repeating it for the same checkout is idempotent. */
    data class Reserve(val checkoutId: String, val quantity: Int) : InventoryRequest

    /** Release a checkout's reservation. Idempotent; releasing nothing is not an error. */
    data class Release(val checkoutId: String) : InventoryRequest

    data object GetAvailable : InventoryRequest
}

sealed interface InventoryReply {
    data object Reserved : InventoryReply
    data object OutOfStock : InventoryReply
    data object Released : InventoryReply
    data class Available(val quantity: Int, val reserved: Map<String, Int>) : InventoryReply
}

val InventoryPort = BallPort<InventoryRequest, InventoryReply>("inventory")
