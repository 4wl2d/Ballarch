> Неканонический перевод. Нормативный и актуальный текст — [английский оригинал](../tutorial.md).

# Учебник: оформить заказ один раз

В этом учебнике строится один Ball — кнопка «Оформить заказ» в приложении магазина. Сервер заказов 24 часа дедуплицирует запросы по заголовку `Idempotency-Key` и умеет найти заказ по этому ключу. Ball не должен ни оформить заказ дважды, ни показать «не оформлен», пока заказ может существовать.

Код лежит в [`examples/place-order`](../../reference/kotlin/examples/place-order/). Для сравнения: [`examples/tutorial`](../../reference/kotlin/examples/tutorial/) отправляет одно письмо через почтовый сервис без ключа идемпотентности, а [магазин](../../reference/kotlin/examples/shop/) показывает на экране статус оформления заказа (checkout). Каждый блок кода скопирован из файла, названного над ним. Тесты запускаются на JDK 21:

```sh
cd reference/kotlin
./gradlew :examples:place-order:test :examples:tutorial:test
```

## 1. Нужен ли здесь Ball?

Ballarch применяется там, где код запрашивает эффект, исход которого может оказаться неопределённым: вызов Idempotent- или NonIdempotent-порта, в том числе вызов другого Ball ([Core §1](../../spec/ballarch-core-2.0.md#1-scope)). Экраны, навигация, кэши, Safe-чтения, код без состояния и запись, единственный эффект которой — одна транзакция в собственной базе данных, остаются обычным кодом. Оформление заказа меняет состояние сервера, а тайм-аут может наступить уже после того, как сервер выполнил запрос, поэтому здесь нужен Ball. Редактированию корзины он не нужен. См. [руководство по выбору](decision-guide.md) и [Структуру приложения](structure.md).

## 2. Назовите эффект, его класс и окно

Порт — это типизированный контракт с классом эффекта. Класс — факт о получателе, и объявляет его приложение ([Core §2.6](../../spec/ballarch-core-2.0.md#26-declaring-effect-classes)). Если класс неизвестен, объявляйте NonIdempotent.

Из [`PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
// ---- the ports: the orders server deduplicates an idempotency key for 24 hours, and finds an order by its key

data class Cart(val items: List<String>)

data class Order(val id: String)

/** `POST /orders` with the `Idempotency-Key` header the runtime supplies. */
val Orders = AdapterPort<Cart, Order>("orders.place", EffectClass.Idempotent)

/** `GET /orders?key=…`: the order placed with that idempotency key, or `null`. */
val OrderLookup = AdapterPort<String, Order?>("orders.lookup", EffectClass.Safe)
```

Сервер дедуплицирует запросы по ключу, поэтому `Orders` — Idempotent. Поиск ничего не меняет, поэтому он Safe. У почтового сервиса из напоминания ключей нет, поэтому его порт — NonIdempotent:

Из [`Reminder.kt`](../../reference/kotlin/examples/tutorial/src/main/kotlin/ballarch/examples/tutorial/Reminder.kt):
```kotlin
data class Email(val to: String, val text: String)

val Mailer = AdapterPort<Email, Unit>("mailer", EffectClass.NonIdempotent)
```

| Класс | Runtime может повторять | После `Unknown` Ball может |
|---|---|---|
| Safe | да | ничего: Safe-вызов никогда не заканчивается `Unknown` |
| Idempotent | да, с одним ключом | повторить эффект с тем же намерением |
| NonIdempotent | никогда | не повторять его без доказательства, что ничего не произошло (R15) |

*Окно дедупликации* — это время, в течение которого сервер помнит ключ, за вычетом времени, которое запрос может провести в пути: здесь 24 часа минус 30 секунд. Объявляется оно на шаге 7.

## 3. Опишите состояние, включая то, чего вы не можете знать

Каждая стадия — вариант sealed-интерфейса. `Checking` — не ошибка: эта стадия говорит, что сервер мог оформить заказ, а мог и нет.

Из [`PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
data class Place(val cart: Cart)

sealed interface PlaceOrderState {
    data object Idle : PlaceOrderState

    /** [repeat]: an earlier call with this [intent] ended `Unknown`. */
    data class Placing(val cart: Cart, val intent: Intent, val call: CallRef<Order>, val repeat: Boolean) : PlaceOrderState

    data class Placed(val orderId: String) : PlaceOrderState

    data class NotPlaced(val reason: String) : PlaceOrderState

    /** The server may or may not have placed the order. Placing it again repeats it with the same [intent]. */
    data class Checking(val cart: Cart, val intent: Intent) : PlaceOrderState
}
```

`Placing` хранит два идентификатора. `CallRef` указывает на вызов, завершение которого важно. `Intent` указывает на эффект, чтобы более поздний вызов мог повторить его с тем же ключом. Оба сериализуемы: этого требует состояние в профиле Durable.

## 4. Решайте через `ctx.call` и `newIntent`

`decide` — чистая функция состояния, запроса и контекста ([R2](../../spec/ballarch-core-2.0.md#51-author-rules)). Она не читает часы, не создаёт ключей и не выполняет ввод-вывод.

Из [`PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
object PlaceOrder : Ball<PlaceOrderState, Place, PlaceOrderState> {
    override val type = "place-order"

    override fun initial(key: String): PlaceOrderState = Idle

    override fun decide(state: PlaceOrderState, request: Place, ctx: RequestContext): Decision<PlaceOrderState, PlaceOrderState> =
        when (state) {
            Idle, is NotPlaced -> place(request.cart, ctx.newIntent(), repeat = false, ctx)
            // "Try again" after Unknown: the same order with the same intent, so the server places it at most once.
            is Checking -> if (request.cart == state.cart) place(state.cart, state.intent, repeat = true, ctx) else reject(state)
            // A double tap joins the order in flight.
            is Placing -> if (request.cart == state.cart) accept(state, state) else reject(state)
            is Placed -> accept(state, state)
        }

    private fun place(cart: Cart, intent: Intent, repeat: Boolean, ctx: Context): Decision<PlaceOrderState, PlaceOrderState> {
        val (call, send) = ctx.call(Orders, cart, timeoutMillis = 60_000, intent = intent)
        val placing = Placing(cart, intent, call, repeat)
        return accept(placing, placing, send)
    }
```

- `ctx.newIntent()` выпускает намерение с меткой времени этого решения; от неё отсчитывается окно.
- `ctx.call(...)` возвращает `CallRef`, который хранится в состоянии, и `Call`, который возвращается как выход.
- Повторное нажатие с той же корзиной присоединяется к заказу, который уже оформляется: состояние то же, выходов нет.
- В `Checking` та же корзина повторяет вызов с прежним намерением, поэтому сервер получает прежний ключ.
- Другая корзина отклоняется: намерение никогда не обозначает другой эффект ([Core §3.2](../../spec/ballarch-core-2.0.md#32-call-lifecycle)).

Runtime фиксирует состояние и вызов одной записью и отправляет вызов только после неё ([R4, R5](../../spec/ballarch-core-2.0.md#52-runtime-rules)). Таймер — тоже вызов: в напоминании это `val (timer, wait) = Timer.after(ctx, request.delayMillis)`.

## 5. Завершайте через `outcomeFor`

Каждый зафиксированный вызов заканчивается ровно одним завершением: `Done`, `NotDone` с доказательством или `Unknown` ([Core §2.2](../../spec/ballarch-core-2.0.md#22-outcomes)). Каким именно, runtime определяет по классу порта и отчёту адаптера.

Из [`PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):
```kotlin
    override fun complete(state: PlaceOrderState, completion: Completion<*>, ctx: Context): Step<PlaceOrderState, PlaceOrderState> {
        if (state !is Placing) return step(state)
        return when (val outcome = completion.outcomeFor(state.call)) {
            is Outcome.Done -> step(Placed(outcome.value.id))
            // After an Unknown, NotDone is about this call only: the earlier one may have placed the order.
            is Outcome.NotDone -> step(if (state.repeat) Checking(state.cart, state.intent) else NotPlaced("${outcome.reason}"))
            is Outcome.Unknown -> step(Checking(state.cart, state.intent))
            null -> step(state) // a completion of another call
        }
    }
```

`outcomeFor(state.call)` возвращает исход, типизированный портом, или `null`, если пришло завершение другого вызова. `when` исчерпывающий, поэтому компилятор заставляет обработать `Unknown`.

Флаг `repeat` важен: после `Unknown` исход `NotDone` относится только к последнему вызову, а более ранний мог оформить заказ. Поэтому Ball возвращается в `Checking`.

У NonIdempotent-порта нет намерения, которое можно повторить. Напоминание записывает `MaybeSent` и останавливается:

Из [`Reminder.kt`](../../reference/kotlin/examples/tutorial/src/main/kotlin/ballarch/examples/tutorial/Reminder.kt):
```kotlin
            // The one completion of the send, typed by the port.
            state is ReminderState.Sending ->
                when (val outcome = completion.outcomeFor(state.call)) {
                    is Outcome.Done -> step(ReminderState.Sent)
                    is Outcome.NotDone -> step(ReminderState.NotSent(outcome.reason.toString()))
                    is Outcome.Unknown -> step(ReminderState.MaybeSent)
                    null -> step(state) // a completion of another call
                }
```

Повторная отправка могла бы отправить письмо дважды, а R15 запрещает её без доказательства, что первое письмо не ушло ([Core §5.1](../../spec/ballarch-core-2.0.md#51-author-rules)). Для Safe-порта `completion.safeOutcomeFor(ref)` возвращает `SafeOutcome` без варианта `Unknown` ([`CatalogSearch.kt`](../../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/CatalogSearch.kt)).

## 6. Чтения и честный интерфейс

Экран читает последнее зафиксированное состояние и не отправляет запрос ([R12](../../spec/ballarch-core-2.0.md#51-author-rules)). `runtime.read(ball, key)` можно вызвать с любого потока: он возвращает `Snapshot(id, revision, state)`, ничего не фиксирует и никогда не ждёт окончания шага. `runtime.watch(ball, key) { snapshot -> ... }` выдаёт текущий снимок, а затем по одному после каждой фиксации ([`CatalogApp.kt`](../../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/app/CatalogApp.kt)).

Из [`ShopApp.kt`](../../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt):
```kotlin
/**
 * What a screen shows for the checkout of [orderId]. It reads the last
 * committed state, so it commits nothing and does not wait behind the Flow's
 * inbox (R12): a screen may call it every time it renders.
 */
fun checkoutStatus(runtime: Runtime, orderId: String): CheckoutReply.Status =
    CheckoutFlow.status(runtime.read(Checkout, orderId).state)
```

В примере place-order функции статуса нет. Экран переводит каждую стадию в один из пяти статусов чистой функцией — например, как в [наброске из руководства по честному интерфейсу](honest-ui.md#пять-статусов):

| Стадия | Статус | Экран сообщает | Основное действие |
|---|---|---|---|
| `Idle` | Idle | пока ничего | «Оформить заказ» |
| `Placing` | Pending | «Оформляем заказ…» | нет; повторное нажатие присоединяется к текущему |
| `Placed` | Done | «Заказ оформлен» | нет |
| `NotPlaced` | NotDone | «Заказ не оформлен» и причина | «Оформить заказ» |
| `Checking` | Unknown | «Мы не смогли подтвердить заказ. Возможно, он оформлен.», но никогда не «ошибка» | «Повторить»: повторяет намерение |

«Повторить» безопасно только потому, что порт Idempotent и Ball повторяет намерение. Для `MaybeSent` в напоминании экран сообщает, что письмо, возможно, отправлено, и не предлагает отправить его снова. Запрос `Status` у напоминания предназначен для других Ball: запрос проходит через решение и фиксацию, а чтение — нет. См. [Честный интерфейс](honest-ui.md).

## 7. Соберите композицию

Корень композиции привязывает каждый порт к адаптеру и задаёт, как runtime с ним обращается. `ORDERS_HORIZON_MILLIS` — 30 секунд: запрос доходит до сервера не позже чем через это время после отправки или не доходит вовсе.

Из [`PlaceOrderApp.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt):
```kotlin
fun Composition.placeOrder(orders: Adapter<Cart, Order>, lookup: Adapter<String, Order?>): Composition =
    ball(PlaceOrder)
        .adapter(
            Orders, orders,
            RetryPolicy(maxAttempts = 3, backoffMillis = 1_000, attemptTimeoutMillis = 10_000),
            // The server keeps a key for 24 hours, less the time a request may still be in transit.
            dedupWindowMillis = 24 * 60 * 60 * 1_000L - ORDERS_HORIZON_MILLIS,
            reconcile = Reconciliation(
                status = OrderLookup,
                horizonMillis = ORDERS_HORIZON_MILLIS,
                statusTimeoutMillis = 5_000,
                query = { it.idempotencyKey },
                verdict = { order -> if (order != null) StatusVerdict.Applied(order) else StatusVerdict.NotApplied },
            ),
        )
        .adapter(OrderLookup, lookup)
```

- **Повторы.** До трёх попыток с задержкой в одну секунду перед повтором, все с одним ключом идемпотентности. Попытка, по которой за 10 секунд не пришло отчёта, заканчивается без ответа и повторяется, пока остаются попытки и время.
- **Окно дедупликации.** Ещё до фиксации runtime отклоняет вызов, дедлайн которого наступает позже, чем через окно после выпуска его намерения ([Core §3.5](../../spec/ballarch-core-2.0.md#35-bounds-and-preflight)). «Повторить» через неделю получит `NotDone(Fault)`, и ничего не будет отправлено. Runtime регистрирует это как ошибку (fault), поэтому считайте окно страховкой и вовремя скрывайте кнопку ([честный интерфейс](honest-ui.md#что-показывать)).
- **Сверка.** Вызов, на который не ответила ни одна попытка, не заканчивается `Unknown` сразу. Когда с последней отправки пройдёт горизонт, runtime один раз обращается к порту поиска: если заказ найден, вызов заканчивается `Done`, если не найден — `NotDone(NotApplied)`, если ответа нет — `Unknown`. Тайм-аут вызова должен быть больше суммы горизонта и тайм-аута запроса статуса.

Расчёт по этой привязке (эти времена не проверяет ни один тест; `ReconciliationTest` проверяет то же правило на меньших значениях): если никто так и не ответит, попытки начнутся на 0-й, 11-й и 22-й секундах; последняя закончится на 25-й, за 35 секунд до дедлайна вызова; запрос поиска уйдёт на 52-й секунде, и вызов завершится к 57-й.

При сборке композиция отклоняет повторы и тайм-аут попытки для NonIdempotent-порта, окно для порта, который не Idempotent, и сверку для Safe-порта. Корень композиции напоминания, [`ReminderApp.kt`](../../reference/kotlin/examples/tutorial/src/main/kotlin/ballarch/examples/tutorial/app/ReminderApp.kt), привязывает почтовый адаптер без параметров: `.adapter(Mailer, mailer)`. Магазин объявляет сверку для своего NonIdempotent-порта списания ([`PaymentsApp.kt`](../../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/PaymentsApp.kt)).

Ваш адаптер для `Orders` сообщает о каждой попытке один раз, никогда не повторяет запрос сам, передаёт `invocation.idempotencyKey` в заголовке `Idempotency-Key` и сообщает `NotApplied` только при наличии доказательства ([Core, приложение D](../../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative)). `AdapterConformance` из `ballarch-testkit` проверяет это на симулированном получателе.

Чтобы запустить всё это, вызовите `Runtime(composition, scheduler, store).start()`. `ThreadScheduler()` выполняет шаги в собственном потоке с циклом; `InlineScheduler(post = ...)` — в потоке, который его создал, например в UI-потоке, и передаёт туда входы из других потоков. `MemoryStore()` ничего не хранит после завершения процесса (Transient); `FileStore(directory)` — Durable, поэтому не выполняйте его записи в UI-потоке. См. [Привязки](bindings.md).

Экран или обработчик отправляет запрос и получает его исход в колбэке, который выполняется в цикле runtime.

Из [`InlineSchedulerTest.kt`](../../reference/kotlin/ballarch-runtime/src/test/kotlin/ballarch/runtime/InlineSchedulerTest.kt):
```kotlin
        var outcome: Outcome<Any?>? = null
        runtime.request<CounterMsg, Any?>(BallId("counter", "c"), CounterMsg.Increment, 1_000) { outcome = it }
        assertEquals(Outcome.Done(CounterReply.Value(1)), outcome)
```

Для place-order запрос выглядит так: `runtime.request<Place, PlaceOrderState>(BallId("place-order", key), Place(cart), timeoutMillis) { outcome -> … }`, а стадию экран берёт из `read` или `watch`.

## 8. Тестируйте решатель как функцию

Решатель — функция, поэтому табличному тесту не нужны ни runtime, ни моки. Время, ключи и намерения даёт `FixedContext`.

Из [`PlaceOrderTest.kt`](../../reference/kotlin/examples/place-order/src/test/kotlin/ballarch/examples/placeorder/PlaceOrderTest.kt):
```kotlin
    @Test
    fun `complete shows NotPlaced only when no earlier call of the order may have placed it`() {
        val order = Order("o-1")
        val refused = Outcome.NotDone(NotDoneReason.NotApplied("connection refused"))
        // whether an earlier call of the order ended Unknown, the outcome -> the next stage
        val rows: List<Triple<Boolean, Outcome<Order>, PlaceOrderState>> = listOf(
            Triple(false, Outcome.Done(order), Placed("o-1")),
            Triple(false, refused, NotPlaced("NotApplied(evidence=connection refused)")),
            Triple(false, Outcome.Unknown(UnknownReason.RetriesExhausted), Checking(cart, intent)),
            Triple(true, Outcome.Done(order), Placed("o-1")),
            Triple(true, refused, Checking(cart, intent)),
            Triple(true, Outcome.Unknown(UnknownReason.LostAfterSend), Checking(cart, intent)),
        )
        for ((repeat, outcome, expected) in rows) {
            val step = PlaceOrder.complete(Placing(cart, intent, call, repeat), Completion(call.key, Orders, outcome), ctx)
            assertEquals(expected, step.state, "repeat=$repeat, $outcome")
            assertEquals(emptyList(), step.outputs)
        }
        val placing = Placing(cart, intent, call, repeat = false)
        assertEquals(placing, PlaceOrder.complete(placing, Completion(CallKey("1.0"), Orders, Outcome.Done(order)), ctx).state)
    }
```

Проверяйте чистоту и при сборке. `BallarchRules.deciderPurity` из `ballarch-archrules` запрещает ввод-вывод, конкурентность, рефлексию, обращения к runtime, часы и случайность в каждом классе пакета, где есть Ball. В place-order и в примере tutorial привязки лежат в подпакете `app`, где нет Ball, и `BallarchRulesTest` применяет все правила к обоим примерам. Свои привязки тоже держите вне пакетов, где лежит Ball.

## 9. Тестируйте при всех сбоях

Тестовый набор запускает настоящий runtime на симулированных часах. `SimPort` выбирает, что случится с каждой попыткой (ответ, потеря запроса, потеря ответа после оформления заказа, отказ в соединении), и записывает, что сделал сервер. `ExhaustiveChooser(maxChoices = 6)` перебирает все последовательности первых шести выборов прогона, а `maybeCrashOnce` добавляет падение перед одной из первых 16 задач планировщика или не добавляет его вовсе.

Из [`PlaceOrderTest.kt`](../../reference/kotlin/examples/place-order/src/test/kotlin/ballarch/examples/placeorder/PlaceOrderTest.kt):
```kotlin
    @Test
    fun `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced`() {
        val finals = sortedMapOf<String, Int>()
        val runs = explore(ExhaustiveChooser(maxChoices = 6)) { chooser ->
            val h = Harness(chooser, Profile.Durable) {
                val orders = SimPort(Orders, scheduler, chooser, truth, FATES, horizonMillis = ORDERS_HORIZON_MILLIS) { Order("o-1") }
                Composition().placeOrder(orders, orders.status { it })
            }
            h.maybeCrashOnce(window = 16)
            h.start()
            h.send(id, Place(cart))
            h.runUntilIdle()
            if (h.runtime.inspect(id) is Checking) { // the user taps "Try again"
                h.send(id, Place(cart))
                h.runUntilIdle()
            }
            h.assertInvariants()
            val placed = h.truth.applied.values.sum()
            val state = h.runtime.inspect(id)
            when (state) {
                is Placed -> assertEquals(1, placed, "choices=${chooser.history}")
                is NotPlaced -> assertEquals(0, placed, "choices=${chooser.history}")
                is Checking, Idle -> assertTrue(placed <= 1, "choices=${chooser.history}")
                else -> fail("unfinished: $state; choices=${chooser.history}")
            }
            finals.merge(state::class.simpleName!!, 1, Int::plus)
        }
        println("place order (Idempotent, Durable, 3 attempts, reconciled, crash window 16, one try again): $runs runs, stages $finals")
        assertTrue(finals.keys.containsAll(setOf("Placed", "NotPlaced", "Checking")), "$finals")
    }
```

`assertInvariants` проверяет по трассе правила runtime, сопоставляя её с тем, что произошло на самом деле: шаги идут последовательно, фиксация предшествует отправке, у каждого вызова одно завершение, исходы честные, слепых повторных отправок нет, на каждый запрос приходит один ответ. `when` проверяет ваше правило: стадия никогда не противоречит серверу.

Попробуйте это сломать. В `complete` замените строку с `NotDone` на `is Outcome.NotDone -> step(NotPlaced("${outcome.reason}"))`. Табличный тест решателя падает на строке с `repeat`. Перебор находит прогон, который заканчивается `NotPlaced` при одном оформленном заказе (`expected: <0> but was: <1>`): ни одна из трёх попыток не получила ответа, причём третья — уже после того, как заказ был оформлен; запрос поиска потерялся, а «Повторить» натолкнулось на отказ в соединении.

`Harness.writeTrace(path)` записывает прогон как трассу соответствия ([Core §9.6](../../spec/ballarch-core-2.0.md#96-conformance-trace)), которую `tools/oracles.py` проверяет без JVM. Переборы runtime пишут трассы во время сборки; из корня репозитория:

```sh
python3 tools/oracles.py reference/kotlin/ballarch-runtime/build/conformance-traces/*.jsonl
```

## 10. Что дальше

- [Структура приложения](structure.md): где размещать Ball, экраны, адаптеры и корень композиции.
- [Честный интерфейс](honest-ui.md), [Привязки](bindings.md) и [Сравнение с Clean Architecture](compared-with-clean.md).
- [Руководство по выбору](decision-guide.md) и [антипаттерны](anti-patterns.md): когда Ballarch окупает свою цену и чего избегать.
- [Магазин](../../reference/kotlin/examples/shop/) — Flow оформления заказа поверх трёх Ball — и [поездка](../../reference/kotlin/examples/trip/), которая сообщает только то, что устанавливает алгебра знания ([Core §4.2](../../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations)).
- [Эталонная реализация на Kotlin](../../reference/kotlin/README.md): модули, команды и что проверяют тесты.
- [Спецификация](../../spec/ballarch-core-2.0.md): модель, правила R1–R16 и соответствие.
