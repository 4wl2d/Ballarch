> Неканонический перевод. Нормативный и актуальный текст — [английский оригинал](../honest-ui.md).

# Честный интерфейс

Экран может показывать об эффекте только то, что устанавливает зафиксированное состояние Ball. Это руководство выводит из этого состояния, что показывать: пять статусов для одного эффекта, семь значений знания для операции из нескольких вызовов и правила для снимков, сообщений и доступности. Руководство ненормативное. Оно применяет [Core §2.2](../../spec/ballarch-core-2.0.md#22-outcomes), [§4.2](../../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations), R14 и R15 ([Core §5.1](../../spec/ballarch-core-2.0.md#51-author-rules)).

## Пять статусов

Каждое действие с эффектом на экране находится в одном из пяти статусов. Каждый статус — функция стадии Ball, поэтому экран не хранит собственных флагов:

| Статус | Значение | Стадия place-order | Стадия платежа в магазине |
|---|---|---|---|
| Idle | ничего не запрошено | `Idle` | `New` |
| Pending | вызов открыт; он мог уйти, а мог и не уйти | `Placing` | `Charging` |
| Done | эффект произошёл | `Placed` | `Captured` |
| NotDone | эффекта не было: есть доказательство или записанный отказ | `NotPlaced` | `NotCaptured` |
| Unknown | эффект мог произойти, а мог и нет | `Checking` | `Unresolved` |

`Pending` покрывает и «ещё не отправлен», и «отправлен, ответа пока нет». Решатель не видит фаз вызова, и экрану они тоже не нужны. Вызов Safe-порта, например поиск в каталоге, никогда не доходит до `Unknown`, и `safeOutcomeFor` даёт его завершению тип без этого варианта. Стадии описаны в [`PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt) и [`PaymentBall.kt`](../../reference/kotlin/examples/shop/payments/src/main/kotlin/ballarch/examples/shop/payments/PaymentBall.kt).

Поместите это отображение в чистую функцию рядом с Ball.

Набросок: эталонного кода нет, соответствие не заявляется.

```kotlin
enum class UiStatus { Idle, Pending, Done, NotDone, Unknown }

fun uiStatus(state: PlaceOrderState): UiStatus = when (state) {
    PlaceOrderState.Idle -> UiStatus.Idle
    is PlaceOrderState.Placing -> UiStatus.Pending
    is PlaceOrderState.Placed -> UiStatus.Done
    is PlaceOrderState.NotPlaced -> UiStatus.NotDone
    is PlaceOrderState.Checking -> UiStatus.Unknown
}
```

В `when` нет `else`, поэтому код с новой стадией не скомпилируется, пока кто-нибудь не решит, что экран для неё показывает. Подавайте в функцию результат `runtime.read(PlaceOrder, key)` или типизированного `watch` ([руководство по структуре](structure.md#чтение)). В магазине есть представления того же состояния в виде типов ответа, `PaymentBall.status` и `CheckoutFlow.status`, и `checkoutStatus` применяет одно из них к результату чтения. `PaymentBall.status` отвечает `Pending` и для `Charging`, и для `Unresolved`, поэтому экран, которому нужно отличать Pending от Unknown, сам отображает `PaymentState`, как в таблице.

## Что показывать

| Статус | Сообщение | Основное действие |
|---|---|---|
| Idle | нет | «Оформить заказ», активна |
| Pending | «Оформляем заказ…»; «Проверяем заказ…», пока идёт повтор с прежним намерением (intent) | неактивна; второе нажатие присоединяется к заказу, который уже в пути |
| Done | «Заказ o-1 оформлен.» | «Открыть заказ» |
| NotDone | «Заказ не оформлен: (причина). Ничего не заказано.» | «Оформить заказ», активна; новый заказ получает новое намерение |
| Unknown от Idempotent-порта с намерением | «Мы не смогли подтвердить заказ. Возможно, он оформлен.» | «Повторить»: повторяет заказ с тем же намерением |
| Unknown от NonIdempotent-порта | «Проверяем платёж. Не оплачивайте повторно.» | нет; «Написать в поддержку», если ситуация не прояснится |

Таблица опирается на четыре правила.

1. **Никогда не показывайте `Unknown` как неудачу** — ни словами, ни цветом, ни значком. Пользователь, который прочитал «не удалось», может заплатить ещё раз. Ball не должен выдавать `Unknown` за доказательство того, что ничего не произошло (R15).
2. **Никогда не делайте NonIdempotent-отправку снова доступной после `Unknown`.** Ball не должен запрашивать тот же эффект, пока не получит доказательство, что первый вызов эффекта не имел (R15). Платёж в магазине после `Unknown` никогда не списывает деньги повторно: `Unresolved` ждёт оператора.
3. **«Повторить» повторяет то же намерение.** Вызовы, которые называют одно намерение, несут один ключ идемпотентности, поэтому сервер с дедупликацией применит не больше одного из них ([Core §3.2](../../spec/ballarch-core-2.0.md#32-call-lifecycle)). `PlaceOrderTest` `trying again after Unknown repeats the order with the same key, which the server does not place twice` проверяет четыре попытки с одним ключом и один заказ.
4. **«Повторить» действует, пока не закончилось окно дедупликации.** После него runtime отклоняет повтор на предварительной проверке (preflight) и ничего не отправляет (`PlaceOrderTest` `a week later the deduplication window refuses to try again, and nothing is sent`). Такой отказ — ошибка предварительной проверки: запрос заканчивается `NotDone(Fault)`, а runtime записывает ошибку, которую [руководство по структуре](structure.md#коды-статуса-http) отображает в HTTP 500. Считайте это страховкой, а не обычным путём пользователя. Прячьте кнопку, как только пройдёт `intent.issuedAt` плюс окно привязки ([`PlaceOrderApp.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/app/PlaceOrderApp.kt)) за вычетом тайм-аута вызова, и предлагайте вместо неё «Проверить заказы». Или пусть `decide` сам сравнит `ctx.now` с `state.intent.issuedAt` и отклонит запоздавший повтор: тогда вызывающий получит `NotDone(Refused)`, а не ошибку.

`NotDone` после повтора ничего не говорит о прежнем вызове, который мог оформить заказ. Поэтому place-order показывает `NotPlaced` только для первого вызова:

Из [`reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):

```kotlin
        return when (val outcome = completion.outcomeFor(state.call)) {
            is Outcome.Done -> step(Placed(outcome.value.id))
            // After an Unknown, NotDone is about this call only: the earlier one may have placed the order.
            is Outcome.NotDone -> step(if (state.repeat) Checking(state.cart, state.intent) else NotPlaced("${outcome.reason}"))
            is Outcome.Unknown -> step(Checking(state.cart, state.intent))
            null -> step(state) // a completion of another call
        }
```

`PlaceOrderTest` `under every fault and crash point no order is placed twice, and no Unknown is shown as NotPlaced` перебирает эти случаи. У каждой из не более чем трёх попыток четыре варианта: ответ получен, запрос потерян, ответ потерян после оформления, соединение отклонено. У чтения статуса вариантов два. В каждом прогоне либо нет падения, либо одно падение перед любой из первых 16 задач, а затем одно «Повторить», если заказ остался в `Checking`. Тест проверяет, что `Placed` означает один заказ, `NotPlaced` — ни одного, а `Checking` — не больше одного. Он печатает 1727 прогонов: 1352 заканчиваются в `Placed`, 87 — в `NotPlaced`, 285 — в `Checking` и 3 — в `Idle`; в этих трёх падение случилось до фиксации решения о заказе, поэтому ничего не отправлено (печатает этот тест).

То же третье состояние есть в Google Play Billing. По состоянию на 2026-10-27 покупка может быть в состоянии `PENDING`, и приложению следует «выдавать доступ, только когда состояние — PURCHASED» ([Integrate the Google Play Billing Library](https://developer.android.com/google/play/billing/integrate), дата обращения: 2026-10-27).

## Операции из нескольких вызовов

Flow, который отвечает за несколько вызовов, сообщает одно из семи значений знания ([Core §4.2](../../spec/ballarch-core-2.0.md#42-knowledge-of-composite-operations)). Для поездки из рейса и отеля:

| Знание | Сообщение | Следующее действие |
|---|---|---|
| Done | «Поездка забронирована.» | показать бронирования |
| NotDone | «Ничего не забронировано.» | предложить начать заново |
| NoEffects | нет: операция ничего не запрашивала | как Idle |
| Partial | «Часть поездки прошла: рейс забронирован, отель — нет.» | показать каждую часть; предложить отменить то, что прошло, или обратиться в поддержку |
| SomeDone | «Не завершено. Часть прошла, проверяем остальное.» | без повтора; показать то, что известно |
| NotAllDone | «Не завершено; проверяем, прошло ли что-нибудь.» | без повтора |
| Unknown | «Проверяем бронирование…» | без повтора |

«Проверяем» правдиво, пока Flow ещё работает. Когда он завершился с `SomeDone`, `NotAllDone` или `Unknown`, само собой больше ничего не выяснится. Напишите «Мы не смогли подтвердить бронирование», передайте случай в поддержку вместе с записью Flow и всё равно никогда не пишите «не удалось».

`TripFlowTest` `the trip's knowledge admits the real bookings under every fate and crash point` проверяет то, от чего это защищает. Двузначный статус ошибся в 1190 прогонах, и все они — среди тех 5565, в которых поездка прекратила компенсацию. Алгебра разделила эти 5565 прогонов на `NotAllDone` (2977), `Unknown` (2037), `SomeDone` (379) и `Partial` (172), и ни одно утверждение не противоречило бронированиям.

Ответ всего с тремя исходами теряет информацию: `Partial`, `NotAllDone` и `SomeDone` превращаются в `Unknown` ([Core §7.4](../../spec/ballarch-core-2.0.md#74-composition-closure)). Кладите знание в тип ответа, как это делает `TripReply.Status` в примере с поездкой.

## Снимки помечаются

`Runtime.read` возвращает `Snapshot` (снимок): зафиксированное состояние и ревизию, в которой оно зафиксировано. То, что экран выводит из снимка, верно для этой ревизии.

Операция из нескольких вызовов добавляет ловушку. Статус, который считает невыполненными вызовы, ещё не сделанные Ball, описывает только момент своего составления, и более поздний вызов может ему противоречить. Core называет это снимком операции. Снимок должен об этом сообщать, например нести стадию операции, и никогда не должен выдаваться за исход (R14). Ответ поездки несёт свою стадию, и результатом считается только ответ в завершённой стадии:

Из [`reference/kotlin/examples/trip/src/main/kotlin/ballarch/examples/trip/api/TripApi.kt`](../../reference/kotlin/examples/trip/src/main/kotlin/ballarch/examples/trip/api/TripApi.kt):

```kotlin
fun settledKnowledge(reply: TripReply): Knowledge? = when (reply) {
    is TripReply.Status -> reply.knowledge.takeIf { reply.stage == Stage.Settled }
}
```

Поэтому, пока поездка идёт, показывайте «Бронируем отель… (рейс забронирован)», а «Поездка забронирована» — только после её завершения. В переборе примера с поездкой статус, который считал вызовы без завершения невыполненными, оказался опровергнут в 5920 прогонах (это проверяет тот же тест `TripFlowTest`). `CheckoutReply.Status` в магазине тоже несёт свою фазу.

## Сообщения и навигация — это состояние

Руководство Google по слою UI хранит сообщение для пользователя в состоянии UI: когда сообщение показано, «UI должен сообщить об этом ViewModel» ([UI events](https://developer.android.com/topic/architecture/ui-layer/events), дата обращения: 2026-10-27). В Ballarch исход уже является состоянием Ball: `NotPlaced(reason)` остаётся до следующего заказа. UI подтверждает лишь то, что показал сообщение для одной ревизии. Храните последнюю показанную ревизию в состоянии UI и показывайте snackbar, когда снимок с более высокой ревизией доходит до `Placed` или `NotPlaced`. Сообщение, которое должно пережить экран, например баннер, который висит, пока пользователь его не закроет, — это бизнес-состояние: дайте Ball запрос `Dismiss`, который переводит его дальше.

Навигация устроена так же ([руководство по структуре](structure.md#навигация)).

## В HTTP и в отчётах агентов

Те же правила действуют для HTTP-ответа. Бэкенд отвечает на `Unknown` кодом 202 и ресурсом статуса, никогда не 5xx и не 504. [Таблица HTTP в руководстве по структуре](structure.md#коды-статуса-http) отображает каждый исход. Клиент, получивший 202, показывает Pending или Unknown, а не неудачу.

Итоговый отчёт агента следует таблице знания. Его статус — значение знания о задаче, вычисленное из исходов вызовов инструментов, и только текстовое описание приходит от модели ([руководство по агентам](ai-agents.md)).

## Доступность

- **Текст, а не только цвет.** Критерий успеха 1.4.1 WCAG 2.2 требует, чтобы цвет «не был единственным визуальным средством передачи информации» ([WCAG 2.2](https://www.w3.org/TR/WCAG22/#use-of-color), дата обращения: 2026-10-27). Дайте `Unknown` собственные слова и значок, отличные от тех, что у ошибки.
- **Сообщайте об изменениях, не перемещая фокус.** Критерий 4.1.3 требует, чтобы сообщения о статусе доходили до вспомогательных технологий, не получая фокуса ([WCAG 2.2](https://www.w3.org/TR/WCAG22/#status-messages)). На Android сделайте текст статуса вежливой живой областью (polite live region): [`View.setAccessibilityLiveRegion`](https://developer.android.com/reference/android/view/View#setAccessibilityLiveRegion(int)) или [`LiveRegionMode.Polite`](https://developer.android.com/reference/kotlin/androidx/compose/ui/semantics/LiveRegionMode) в Compose. По состоянию на 2026-10-27 [`View.announceForAccessibility`](https://developer.android.com/reference/android/view/View#announceForAccessibility(java.lang.CharSequence)) объявлен устаревшим в API level 36. На iOS отправляйте [`UIAccessibility.Notification.announcement`](https://developer.apple.com/documentation/uikit/uiaccessibility/notification/announcement). Дата обращения ко всем источникам — 2026-10-27.
- **Объясняйте, почему элемент управления неактивен.** Неактивная кнопка «Оформить заказ» стоит рядом с текстом «Оформляем заказ…», и вспомогательные технологии его читают.
