package ballarch.examples.shop.app

import ballarch.examples.shop.payments.ChargeRequest
import ballarch.examples.shop.payments.ChargeResult
import ballarch.examples.shop.payments.ChargeStatus
import ballarch.examples.shop.payments.PaymentBall
import ballarch.examples.shop.payments.ProviderCharge
import ballarch.examples.shop.payments.ProviderStatus
import ballarch.examples.shop.payments.StatusRequest
import ballarch.runtime.Adapter
import ballarch.runtime.Composition
import ballarch.runtime.Reconciliation
import ballarch.runtime.StatusVerdict

/**
 * Adapters for the payment provider; supplied by the deployment, for example
 * the charge and status adapters of `HttpProviderAdapters`, or a simulator.
 */
class ProviderAdapters(
    val charge: Adapter<ChargeRequest, ChargeResult>,
    val status: Adapter<StatusRequest, ChargeStatus>,
    /** Time after which an unanswered charge can no longer be applied by the provider. */
    val horizonMillis: Long,
    /** How long the provider's status endpoint may take to answer. */
    val statusTimeoutMillis: Long = 1_000,
)

/** How long a charge's answer is awaited before the runtime waits out the horizon and reads the status. */
private const val CHARGE_ANSWER_MILLIS = 1_000L

/**
 * Composition root of the payments feature alone (slice S2). The charge port is
 * NonIdempotent, so it takes no retries; the runtime reconciles a charge left
 * without an answer through the provider's status.
 */
fun paymentsComposition(provider: ProviderAdapters): Composition =
    Composition()
        .ball(PaymentBall(chargeTimeoutMillis = CHARGE_ANSWER_MILLIS + provider.horizonMillis + provider.statusTimeoutMillis))
        .adapter(
            ProviderCharge, provider.charge,
            reconcile = Reconciliation(
                status = ProviderStatus,
                horizonMillis = provider.horizonMillis,
                statusTimeoutMillis = provider.statusTimeoutMillis,
                query = { StatusRequest(it.request.orderId) },
                verdict = { status ->
                    when (status) {
                        is ChargeStatus.Charged -> StatusVerdict.Applied(ChargeResult.Charged(status.reference))
                        ChargeStatus.NoCharge -> StatusVerdict.NotApplied
                    }
                },
            ),
        )
        .adapter(ProviderStatus, provider.status)
