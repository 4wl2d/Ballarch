// Seeded violations of the Core 2 author rules. Each package contains exactly
// one kind of violation, so the tests can show which rule detects which kind.
@file:Suppress("unused")

package ballarch.archrules.fixtures

import ballarch.kernel.Ball
import ballarch.kernel.Decision
import ballarch.kernel.RequestContext
import ballarch.kernel.accept

/** A pure Ball: the negative control. `java.time.Duration` as a value is allowed. */
object CleanBall : Ball<Int, Int, Int> {
    override val type = "clean"
    override fun initial(key: String) = 0
    override fun decide(state: Int, request: Int, ctx: RequestContext): Decision<Int, Int> =
        accept(state + request + java.time.Duration.ofMillis(ctx.now).toSecondsPart(), state)
}
