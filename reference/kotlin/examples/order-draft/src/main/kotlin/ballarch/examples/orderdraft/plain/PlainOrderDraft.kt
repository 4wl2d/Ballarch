package ballarch.examples.orderdraft.plain

/** S0 baseline: the same requirements as a plain Kotlin class. */
class PlainOrderDraft {
    var quantity: Int = 1
        private set

    /** Returns false and leaves the quantity unchanged when [value] is outside 1–20. */
    fun setQuantity(value: Int): Boolean {
        if (value !in 1..20) return false
        quantity = value
        return true
    }
}
