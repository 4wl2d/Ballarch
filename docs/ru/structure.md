> Неканонический перевод. Нормативный и актуальный текст — [английский оригинал](../structure.md).

# Структура приложения

Это руководство показывает, где размещать код в приложении на Ballarch: какие части становятся Ball и как ими пользуются экраны, фоновые задачи и обработчики HTTP. Руководство ненормативное: где оно расходится со [спецификацией](../../spec/ballarch-core-2.0.md), действует спецификация. Код взят из [примеров на Kotlin](../../reference/kotlin/examples/).

## Порог и ступени

**Порог.** Ball нужен компоненту, который вызывает порт с эффектом, способным остаться неопределённым: Idempotent- или NonIdempotent-порт, в том числе порт другого Ball ([Core §1](../../spec/ballarch-core-2.0.md#1-scope)). Всё остальное — обычный код, который Core не ограничивает.

Поднимайтесь ровно настолько, насколько нужно фиче:

| Ступень | Когда | Что писать | Пример |
|---|---|---|---|
| 0 | нет вызовов Idempotent или NonIdempotent | обычный код: класс, редьюсер, репозиторий | [`PlainOrderDraft.kt`](../../reference/kotlin/examples/order-draft/src/main/kotlin/ballarch/examples/orderdraft/plain/PlainOrderDraft.kt) |
| 1 | один эффект, возможно повторяемый | один Ball в одном файле: его стадии, `decide`, `complete`, порт с классом эффекта и три исхода | [`PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt) |
| 2 | несколько эффектов, о которых сообщают как об одной операции, с компенсацией | Flow, ответ которого — знание о завершённой операции, в профиле Durable | [пример с поездкой](../../reference/kotlin/examples/trip/) |

Ball без эффектов, например [Ball черновика заказа](../../reference/kotlin/examples/order-draft/src/main/kotlin/ballarch/examples/orderdraft/OrderDraft.kt), допустим, но никогда не обязателен.

## Раскладка по файлам

Держите фичу вместе: её Ball, порты, сообщения, адаптеры и тесты ([Core §7.1](../../spec/ballarch-core-2.0.md#71-module-structure)):

```text
app/
  Composition.kt        the composition root: one line per Ball and per binding, such as placeOrder(orders, lookup)
placeorder/
  PlaceOrder.kt         ports, stages and the Ball: decider code, checked by deciderPurity
  PlaceOrderTest.kt     the decider as a function, and one fault exploration
placeorder/app/
  PlaceOrderApp.kt      the binding Composition.placeOrder: retries, window, reconciliation
placeorder/ui/
  PlaceOrderScreen.kt   reads or watches the Ball, renders, sends requests
placeorder/http/
  OrdersHttp.kt         adapters: the HTTP client, DTOs and their mapping
```

§7.1 предлагает один пакет, но эталонное правило чистоты считает кодом решателя каждый класс в пакете, где лежит Ball. Поэтому экраны, адаптеры и привязки (bindings) уходят в подпакеты, как в магазине (shop). [Пример place-order](../../reference/kotlin/examples/place-order/) тоже следует правилу: в `PlaceOrder.kt` лежит код решателя, в `app/PlaceOrderApp.kt` — привязка, и `BallarchRulesTest` проверяет оба файла всеми правилами. Корень композиции магазина перечисляет каждый Ball и каждую привязку по одному разу:

Из [`reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt`](../../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt):

```kotlin
fun shopComposition(provider: ProviderAdapters, initialStock: Int): Composition =
    paymentsComposition(provider)
        .ball(Checkout)
        .ball(InventoryBall(initialStock))
        .ball(OrderBall)
        .subscribe(PaymentResolved, "checkout") { it.orderId }
```

Отдельные API-модули для каждой фичи необязательны; если они есть, как в магазине, сборка проверяет, что фичи используют друг у друга только публичные типы (R10).

Главное: делайте каждый вызов Idempotent- или NonIdempotent-порта из Ball. Код, который делает такой вызов где-то ещё, находится вне гарантий Core.

## Что не является Ball

| Что | Где ему место |
|---|---|
| Экран, Activity, Fragment, ViewModel, представление SwiftUI | код UI, который читает Ball и отправляет им запросы |
| Репозиторий, DAO | обычный код поверх базы данных или кэша |
| Use case | функция или ничего; use case, который только передаёт запрос дальше, добавляет слой, но не гарантию |
| Кэш, модель чтения, источник страниц (paging source) | обычный код; Ball обращается к нему через Safe-порт |
| Ввод в форме, фокус, позиция прокрутки | состояние UI-фреймворка |
| Маппер, DTO | внутри адаптера, которому он нужен |
| Сервис без состояния: расчёт цен, валидация, форматирование | чистая функция |
| Стек навигации | навигатор |
| CRUD внутри одной транзакции БД | обычный код: транзакция — его единственный эффект |

## Виды состояния

| Вид | Пример | Где хранится |
|---|---|---|
| Бизнес-состояние и обязательства | оформляемый заказ, платёж, открытые вызовы | состояние Ball, которое фиксирует runtime |
| Состояние представления | активна ли кнопка, строка статуса | выводится из состояния Ball чистой функцией и никогда не хранится |
| Временный ввод | набираемый текст, выбранная вкладка | UI-фреймворк и его API сохранённого состояния |
| Удалённые данные для чтения | товары, результаты поиска, цены | кэш; Ball, которому они нужны, вызывает Safe-порт |
| Навигация | стек возврата (back stack) | навигатор |

Выведенное состояние представления не может показать «оформлен», пока Ball находится в `Checking`; как его выводить, описано в [руководстве по честному интерфейсу](honest-ui.md).

## Чтение

Код вне Ball получает зафиксированное состояние Ball чтением, а не запросом (R12, [Core §5.1](../../spec/ballarch-core-2.0.md#51-author-rules)). Чтение ничего не фиксирует, не попадает во входящую очередь (inbox) и работает из любого потока. Во время шага оно возвращает состояние до шага (`RuntimeTest` `a read during a step sees the state before it`).

Из [`reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt`](../../reference/kotlin/examples/shop/app/src/main/kotlin/ballarch/examples/shop/app/ShopApp.kt):

```kotlin
fun checkoutStatus(runtime: Runtime, orderId: String): CheckoutReply.Status =
    CheckoutFlow.status(runtime.read(Checkout, orderId).state)
```

`read(ball, key)` типизирован по Ball; `read(id)` возвращает `Snapshot<Any?>`. `ShopAppTest` `a screen reads the checkout status, which commits nothing` проверяет, что чтение не добавляет ни одной записи в хранилище. Типизированный `watch` передаёт текущий `Snapshot`, а затем по одному после каждой фиксации, в цикле runtime:

Из [`reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/app/CatalogApp.kt`](../../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/app/CatalogApp.kt):

```kotlin
fun showSearch(runtime: Runtime, screen: String, show: (Display) -> Unit) {
    runtime.watch(CatalogSearchBall, screen) { show(it.state.display) }
}
```

Эталонный `watch` не умеет удалять слушателя. Регистрируйте по одному слушателю на экземпляр в области жизни приложения и передавайте значения в поток (flow), например `StateFlow`, на который подписываются экраны. Или вызывайте `read` при каждой отрисовке экрана.

- **Ball из Ball.** Ball читает другой Ball только запросом, который получатель принимает, не меняя своего состояния, с немедленным ответом и без выходов (R12).
- **Внешние данные.** Ball читает их через Safe-порт адаптера, например `OrderLookup` в place-order. Safe-вызов никогда не заканчивается `Unknown`.
- **Несколько Ball на одном экране.** Прочитайте каждый и объедините их в одной чистой функции UI.

## Навигация

Навигация следует за состоянием Ball и обрабатывается один раз. Она никогда не бывает выходом Ball: выходы Ball — только вызовы, ответы, уведомления и отмены. Руководство Google говорит то же о событиях ViewModel: они «всегда должны приводить к обновлению состояния UI» ([UI events](https://developer.android.com/topic/architecture/ui-layer/events), дата обращения: 2026-10-27).

Экран переходит к чеку, когда видит `Placed`, и хранит обработанную ревизию в сохранённом состоянии UI, чтобы следующая отрисовка не перешла туда снова.

Мастер, шаги которого только собирают ввод, — это состояние UI плюс один запрос при отправке. Мастер, шаги которого делают вызовы с эффектами, например «забронировать место, потом оплатить», — это один Flow ([Core §7.2](../../spec/ballarch-core-2.0.md#72-workflows)). Каждый экран показывает одну из его стадий.

## Двойная отправка и «побеждает последний»

Шаги одного экземпляра никогда не перекрываются (R3). Второе нажатие попадает в `decide` после того, как решение по первому зафиксировано, и застаёт стадию, которую это решение оставило:

Из [`reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt`](../../reference/kotlin/examples/place-order/src/main/kotlin/ballarch/examples/placeorder/PlaceOrder.kt):

```kotlin
        when (state) {
            Idle, is NotPlaced -> place(request.cart, ctx.newIntent(), repeat = false, ctx)
            // "Try again" after Unknown: the same order with the same intent, so the server places it at most once.
            is Checking -> if (request.cart == state.cart) place(state.cart, state.intent, repeat = true, ctx) else reject(state)
            // A double tap joins the order in flight.
            is Placing -> if (request.cart == state.cart) accept(state, state) else reject(state)
            is Placed -> accept(state, state)
        }
```

`PlaceOrderTest` `decide places a new order, joins a double tap, and repeats an unknown one with its intent` проверяет каждую ветку. Неактивная кнопка помогает пользователю, но не защищает от второго заказа. С `InlineScheduler` в главном потоке запрос решается, фиксируется и получает ответ до того, как `request` вернёт управление (`InlineSchedulerTest`), поэтому уже следующий кадр показывает заказ как ожидающий.

В поиске должен побеждать последний запрос. Ball каталога отменяет предыдущий поиск, чтобы не делать лишней работы, а его `complete` читает только завершение последнего вызова (`CatalogSearchTest`):

Из [`reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/CatalogSearch.kt`](../../reference/kotlin/examples/catalog/src/main/kotlin/ballarch/examples/catalog/CatalogSearch.kt):

```kotlin
        val latest = state.pending ?: return step(state)
        val outcome = completion.safeOutcomeFor(latest.call) ?: return step(state) // an older search: never replaces the display
```

Присоединение, отказ, постановка в очередь и замена — это ветки `decide` по текущей стадии, а не DSL допуска запросов.

## Смерть процесса

Мобильные системы завершают процессы без предупреждения. На Android «система может убить процесс в любой момент, чтобы освободить память», и вызов `onDestroy()` не гарантирован ([Processes and app lifecycle](https://developer.android.com/guide/components/activities/process-lifecycle)). В Compose изменения `SavedStateHandle`, сделанные в фоне, «могут быть потеряны», если процесс убит ([Saved State module](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-savedstate)). На iOS «система по мере необходимости завершает фоновые приложения», чтобы освободить ресурсы ([Preparing your UI to run in the background](https://developer.apple.com/documentation/uikit/preparing-your-ui-to-run-in-the-background)). Дата обращения ко всем источникам — 2026-10-27.

Отсюда:

1. **Runtime живёт столько же, сколько приложение.** Создавайте каждый runtime один раз на процесс — в `Application.onCreate` или в контейнере зависимостей приложения. Их может быть два: Transient в главном потоке для Ball интерфейса и Durable в фоновом цикле (пункт 2). Ball, которые вызывают друг друга, работают в одном runtime. Runtime, привязанный к ViewModel, умирает вместе с экраном, и его открытые вызовы теряют свои завершения.
2. **Ball с эффектами работают в профиле Durable.** Их хранилище заменяет запись одного экземпляра в одной транзакции, при каждом старте выдаёт новую инкарнацию (incarnation) и не допускает второй runtime: исключает его или отсекает его записи (fencing) ([Core §6](../../spec/ballarch-core-2.0.md#6-profiles), R16). Такое хранилище можно построить на Room, SQLite или Core Data. Эталон поставляет `MemoryStore` и `FileStore` для JVM, но не мобильное хранилище, и runtime на Swift в нём нет ([руководство по привязкам](bindings.md)). Запускайте Durable-runtime в фоновом цикле (`ThreadScheduler`), потому что каждая фиксация пишет в хранилище; для Transient подходит `InlineScheduler(post = Handler(Looper.getMainLooper())::post)`.
3. **Восстановление происходит при старте.** Runtime повторно отправляет Idempotent-вызов с исходным ключом, пока остаются попытки и время. NonIdempotent-вызов, который мог уйти, сверяется через порт со сверкой, а без такого порта заканчивается `Unknown(RecoveredAfterCrash)` ([Core §3.7](../../spec/ballarch-core-2.0.md#37-crash-and-recovery)).
4. **WorkManager и BGTaskScheduler только будят runtime.** Когда наблюдатель видит, что Ball перешёл в стадию ожидания, он планирует задачу, которая заново запустит убитый процесс. Задача при необходимости запускает runtime, ждёт, пока чтение не покажет, что Ball вышел из этой стадии (для place-order — из `Placing`), и завершается. Она никогда не отправляет запрос и никогда не возвращает `Result.retry()`, чтобы его повторить: повторяет только runtime (R8). WorkManager сохраняет работу «между перезапусками приложения и перезагрузками устройства» ([WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent)), а для задачи BGTaskScheduler «система запускает приложение, чтобы выполнить задачу» ([background tasks](https://developer.apple.com/documentation/uikit/using-background-tasks-to-update-your-app)); дата обращения к обоим источникам — 2026-10-27. Если система остановит задачу раньше времени или процесс умрёт между фиксацией и постановкой задачи в очередь, вызов останется в хранилище, и runtime восстановит его при следующем старте.
5. **В сохранённом состоянии лежат только неотправленный ввод и ключ Ball**, например id черновика. Всё остальное читается заново.

В профиле Transient перезапуск забывает вызовы Ball. В примере с поездкой клиент, который бронирует снова после перезапуска Transient-runtime, получает ложное утверждение о завершённой операции в 608 из 3073 завершённых прогонов, а в 152 прогонах рейс бронируется дважды (`TripFlowTest` `the trip requires the Durable profile`). Перебор в place-order роняет Durable-runtime перед любой из первых 16 задач и проверяет, что ни один заказ не оформлен дважды и ни один `Unknown` не показан как `NotPlaced` (`PlaceOrderTest`).

## Офлайн

- **Короткий обрыв связи.** Попытка, у которой потерялся запрос или ответ, заканчивается без ответа, и runtime повторяет Idempotent-вызов в пределах его дедлайна; тайм-аут попытки обрывает зависшую попытку раньше. Place-order разрешает три попытки с задержкой 1 с между ними, тайм-аут попытки 10 с и дедлайн 60 с. Если первую попытку отклонили до того, как что-либо отправлено, — например, когда сети нет вовсе, — вызов сразу заканчивается `NotDone`: ничего не заказано.
- **Долгое отсутствие сети.** Не держите вызов открытым: его тайм-аут ограничен (`Limits.maxTimeoutMillis`, по умолчанию десять минут). Когда вызов заканчивается `NotDone` из-за отсутствия сети, Ball может перевести запрос в стадию `Queued`, а не показывать его как неоформленный. Входящий адаптер превращает сигнал платформы в запрос: [`ConnectivityManager.NetworkCallback`](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback) на Android, [`NWPathMonitor`](https://developer.apple.com/documentation/network/nwpathmonitor) на iOS (дата обращения к обоим — 2026-10-27) или задача из пункта 4 с ограничением по сети. Тогда Ball делает вызов с новым намерением (intent): прежний вызов эффекта не имел, а окно намерения отсчитывается от решения, которое его создало. Только `Checking`, где прежний вызов мог дойти до сервера, сохраняет своё намерение на время ожидания.
- **После `Unknown`.** Повторяйте с тем же намерением, пока вызов укладывается в окно. `PlaceOrderTest` `trying again after Unknown repeats the order with the same key, which the server does not place twice` проверяет четыре попытки с одним ключом и один заказ. За пределами окна повтор мог бы примениться дважды, поэтому предварительная проверка (preflight) его отклоняет ([Core §3.5](../../spec/ballarch-core-2.0.md#35-bounds-and-preflight)) и ничего не отправляется (`a week later the deduplication window refuses to try again, and nothing is sent`). Вместо повтора проведите сверку: через Safe-порт статуса или вместе с пользователем и поддержкой.
- **Параллельные офлайн-правки**, которые нужно сливать, лежат вне Core. Правка, сделанная офлайн, — это запрос, который Ball-владелец решает, когда тот дойдёт.

## Бэкенд

- **Ball — это агрегат**: один экземпляр на ключ, например на id заказа. Обработчик HTTP разбирает и аутентифицирует запрос, затем вызывает `runtime.request(target, message, timeoutMillis, idempotencyKey, caller) { outcome -> … }` и передаёт субъект (principal) в `Caller` ([Core §8](../../spec/ballarch-core-2.0.md#8-guidance-non-normative)). Колбэк выполняется в цикле runtime; там и завершайте асинхронный ответ фреймворка (эталонного кода нет).
- **Одна транзакция на решение.** Durable-хранилище атомарно заменяет запись экземпляра: состояние, журнал вызовов, открытые и отвеченные запросы, outbox. Запись становится долговечной до того, как что-либо отправлено. В SQL это одна строка на экземпляр или несколько таблиц в одной транзакции.
- **Существующие таблицы.** Хранилище держит только собственную запись Ball. Доменные таблицы остаются на своих местах, и Ball меняет их через порт адаптера. Вставка с ограничением уникальности по ключу идемпотентности, который выдал runtime, — это Idempotent, если её адаптер отвечает на дубликат уже существующей строкой.
- **Входящие ключи идемпотентности.** Передавайте заголовок `Idempotency-Key` как `idempotencyKey`: он становится идентичностью запроса. Повтор с тем же телом присоединяется к открытому запросу или получает сохранённый ответ; запрос с другим телом получает `NotDone(IdempotencyConflict)` ([Core §3.3](../../spec/ballarch-core-2.0.md#33-inbound-request-lifecycle)). Отвеченные запросы хранятся `Limits.repliedRetentionMillis` (по умолчанию один час), не больше `Limits.repliedRetention` на экземпляр. Публикуйте этот срок как срок действия ключа, как просит черновик IETF ([draft-ietf-httpapi-idempotency-key-header-08](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08) §2.3, от 2026-10-22, дата обращения: 2026-10-27). Окно клиента должно в него укладываться: place-order объявляет 24 часа минус 30 с, поэтому серверу на Ballarch за ним нужно не меньше 24 часов. Дедупликация действует в пределах экземпляра, поэтому называйте экземпляр по данным запроса, например по id заказа, который выбрал клиент. Тогда повтор после окончания срока хранения всё равно застанет стадию Ball.

### Коды статуса HTTP

Отображайте исход `runtime.request` в ответ, который утверждает не больше, чем сам исход:

| Исход | Ответ | Что узнаёт клиент |
|---|---|---|
| `Done(reply)` о законченном запросе | 200 или 201 с `Location` для созданного ресурса | применено; ответ говорит, что произошло, — возможно, это записанный бизнес-отказ |
| `Done(reply)` с ещё не завершённой стадией | 202 со стадией и ссылкой на статус | принято, но не закончено |
| `Done(reply)` со знанием о завершённой операции | 200 со значением знания | окончательно; больше не изменится |
| `NotDone(Refused(reply))` | 4xx в зависимости от ответа: 409 при конфликте с текущей стадией, 422 для запроса, который отклонил решатель | не применено; измените запрос и отправьте снова |
| `NotDone(IdempotencyConflict)` | 422 (черновик, §2.7) | ключ повторно использован с другим телом |
| `NotDone(Overloaded)`, `NotDone(DeadlineBeforeSend)` | 503 с `Retry-After` | не применено; отправьте позже с тем же ключом |
| `NotDone(Fault(message))` | 500 с телом, где сказано, что запрос не применён | не применено; дефект записан |
| `Unknown(reason)` | 202 со ссылкой на ресурс статуса, никогда не 5xx и не 504 | эффект может наступить, а может и нет |

По RFC 9110, после 202 запрос «в итоге может быть обработан, а может и не быть», и ответу следует указывать на монитор статуса ([RFC 9110 §15.3.3](https://www.rfc-editor.org/rfc/rfc9110#section-15.3.3), дата обращения: 2026-10-27). Это и есть `Unknown`. Клиент, который считает 500 или 504 от шлюза неудачей, может отправить запрос ещё раз, уже без ключа. Ресурс статуса отвечает на `GET` чтением, помеченным стадией Ball; клиент опрашивает его или повторяет запрос с тем же ключом, и повтор присоединяется к исходному запросу или получает его ответ. Для повтора, который пришёл, пока первый запрос ещё открыт, черновик просит 409; runtime вместо этого присоединяет повтор к первому запросу. Честны оба варианта.

- **Процессы (workflows).** Многошаговая операция с компенсацией — это один Flow, а не сага, написанная вручную ([Core §7.2](../../spec/ballarch-core-2.0.md#72-workflows)). Он отвечает, когда операция завершена, и его ответ — знание о завершённой операции ([Core §7.4](../../spec/ballarch-core-2.0.md#74-composition-closure)), как в примере с поездкой.
- **Развёртывание.** Runtime перезапускается с зафиксированного состояния и никогда не перевыполняет старые решения новым кодом (R4). Изменённой форме состояния нужны версия и `migrate`; порт или тип Ball перед удалением доводят до закрытия (drain), иначе runtime откажется стартовать ([Core §6.1](../../spec/ballarch-core-2.0.md#61-evolution), `EvolutionTest`). С хранилищем, которое исключает второй runtime или отсекает его записи (R16), поэтапное (rolling) развёртывание не может оставить два runtime, фиксирующих решения в один экземпляр (`OwnershipTest`). Распределение экземпляров по нескольким серверам — это партиционирование, и Core оставляет его вам; эталонный `FileStore` исключает второй runtime только в пределах одного хоста.

## Чего стоит такая структура

Зависимость от runtime, а в профиле Durable — хранилище, которое выполняет контракт; в эталоне нет хранилища на Room, Core Data или SQL. Три долговечные записи на каждый вызов с эффектом и ни одной на чтение (`RuntimeTest`). Один писатель на экземпляр, поэтому горячий ключ становится очередью. Явная стадия на каждый шаг, сериализуемая в профиле Durable. Ограничения перечислены в [Core §10](../../spec/ballarch-core-2.0.md#10-trade-offs-and-known-limitations); когда лучше выбрать что-то другое, говорит [руководство по выбору](decision-guide.md).
