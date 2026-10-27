> Неканонический перевод. Нормативный и актуальный текст — [английский оригинал](../bindings.md).

# Привязки: Ballarch на вашем языке

Правила исходов и знания в Core не зависят от языка ([Core §11](../../spec/ballarch-core-2.0.md#11-interpretation-across-levels-informative)). Эталонный код есть на Kotlin для JVM, на C11 и на SystemVerilog. Это руководство перечисляет, что должен делать любой runtime, показывает, как эталон на Kotlin ложится на Android, и даёт наброски для Swift, Rust и TypeScript.

Каждый блок на Swift, Rust и TypeScript и каждый блок на Kotlin, не скопированный из эталона, помечен **Sketch: no reference code and no conformance claim** (набросок: эталонного кода нет, соответствие не заявляется). Ни один набросок здесь не запускается и не тестируется.

## 1. Контракт runtime

Runtime соответствует Core, если выполняет [Core §9.1](../../spec/ballarch-core-2.0.md#91-a-conforming-runtime) для заявленных профилей. Это сводится к десяти частям. Runtime профиля Transient нужны части 1–8 и 10; профиль Durable добавляет часть 9. Номера R — правила из [Core §5](../../spec/ballarch-core-2.0.md#5-kernel-rules).

1. **Последовательные шаги каждого экземпляра.** Один вход одного экземпляра за раз. Вход, поданный во время шага, ждёт, пока завершатся этот шаг и его отправка. Шаги могут выполняться в потоке, который подал вход, или в цикле событий ([Core §3.1](../../spec/ballarch-core-2.0.md#31-the-instance-step), R3).
2. **Решение, предварительная проверка (preflight), фиксация, отправка.** Фиксируйте новое состояние со всеми его вызовами, ответами и уведомлениями одной атомарной записью. Только после этого отправляйте вызовы, отвечайте и публикуйте уведомления. Отвергнутое или неудавшееся решение ничего не фиксирует (R4, R5).
3. **Таблица вызовов.** Для каждого открытого вызова: фаза (`Pending`, `Sending` и, если сверка поддерживается, `Reconciling`), класс эффекта, записанный при фиксации, попытки, дедлайн, намерение и ключ идемпотентности. Runtime выводит ключ из вызывающего и намерения или из идентичности вызова. Ни один ключ не повторяется, пока получатель может его помнить, в том числе после перезапуска в профиле Transient ([Core §3.2](../../spec/ballarch-core-2.0.md#32-call-lifecycle)).
4. **Одна функция классификации.** Таблица из §3.2, реализованная один раз; её входы — записанный класс и фаза. Через неё проходят ответы, отчёты адаптеров, дедлайны, отмены и перезапуски. Адаптеры только сообщают, что произошло; исход они не выбирают никогда (R7).
5. **Дедлайны, повторы и таймеры.** У каждого вызова есть конечный дедлайн и ровно одно завершение к нему (R6). Повторяются только вызовы Safe- и Idempotent-портов, в пределах бюджета привязки и тайм-аута попытки. Бюджет повторов или тайм-аут попытки на NonIdempotent-порте отвергается при построении композиции (R8). Порт таймера привязан ([Core §2.4](../../spec/ballarch-core-2.0.md#24-time)).
6. **Запросы, вызовы Ball и уведомления.** Входящие запросы дедуплицируются по идентичности запроса. Вызов через порт Ball — это запрос, идентичность которого совпадает с идентичностью вызова; её помнят до дедлайна вызова. Каждый подписчик получает каждое уведомление не более одного раза и по порядку ([§3.3](../../spec/ballarch-core-2.0.md#33-inbound-request-lifecycle), [§3.4](../../spec/ballarch-core-2.0.md#34-ball-to-ball-calls), [§3.6](../../spec/ballarch-core-2.0.md#36-notices)).
7. **Ограничения и предварительная проверка.** Ограничения конечны, а новая работа отвергается до того, как её приняли. Предварительная проверка отвергает намерение на порте, который не является Idempotent-портом адаптера, и дедлайн за пределами объявленного окна дедупликации ([Core §3.5](../../spec/ballarch-core-2.0.md#35-bounds-and-preflight), R11).
8. **Чтения.** Последнее зафиксированное состояние и его ревизия. Чтение ничего не фиксирует, не встаёт в очередь и не создаёт записей (R12).
9. **Профиль Durable.** Хранилище, которое атомарно заменяет запись, выдаёт новую инкарнацию при каждом старте и исключает второй runtime или отсекает его (fencing, R16), либо хранилище, задокументированное как рассчитанное на один runtime; тогда это обеспечивает развёртывание (R13). Упреждающая запись (write-ahead) перед каждой попыткой. Восстановление по строкам перезапуска из таблицы §3.2. Проверки при старте из [§6.1](../../spec/ballarch-core-2.0.md#61-evolution) до любой записи и ещё раз после того, как новая инкарнация получена: с хранилищем, которое отсекает записи (fencing), заменённый runtime до этого момента ещё может зафиксировать запись ([Core §6](../../spec/ballarch-core-2.0.md#6-profiles)).
10. **Генератор трассы.** События [формата трассы v1](../../tools/TRACE-FORMAT.md) в те моменты, которые формат называет, чтобы одна программа проверки могла оценить перебранные прогоны.

## 2. Kotlin и Android

Ядро (`ballarch-kernel`) и runtime (`ballarch-runtime`) написаны на обычном Kotlin, собираются и тестируются только на JVM ([эталон на Kotlin](../../reference/kotlin/README.md)). Сборки под Android и тестов на устройстве здесь нет; этот раздел сопоставляет части, но не утверждает, что сопоставление запускалось.

### Планировщики

Из [`Scheduler.kt`](../../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Scheduler.kt):

```kotlin
public class InlineScheduler(
    private val post: (Runnable) -> Unit,
    private val timer: ScheduledExecutorService = defaultTimer,
    private val clock: () -> Long = System::currentTimeMillis,
) : Scheduler {
```

- `InlineScheduler` выполняет шаги в потоке, который его создал. На Android создавайте его в главном потоке: `InlineScheduler(post = Handler(Looper.getMainLooper())::post)`. Запрос, поданный в этом потоке, решается, фиксируется и получает ответ ещё до возврата из `request`, если не ждёт адаптер. Отчёты из других потоков приходят через `post`. `InlineSchedulerTest` проверяет это с тестовым `post`, а не с настоящим `Handler`.
- `ThreadScheduler(name)` выполняет шаги в отдельном выделенном потоке.

Записи в хранилище выполняются в потоке планировщика. Поэтому Transient-Ball интерфейса, например поиск, работает на `InlineScheduler` с `MemoryStore`, а Durable-Ball, например заказ, — на `ThreadScheduler` с долговечным хранилищем: в главном потоке нет записи на диск.

Ball, которые вызывают друг друга, работают в одном runtime: вызов через порт Ball достигает только типов Ball из своей композиции. Держите runtime на уровне приложения, а не по одному на экран ([руководство по структуре](structure.md)).

### Адаптеры на корутинах

Из [`Adapter.kt`](../../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Adapter.kt):

```kotlin
public fun interface Adapter<Req, Res> {
    public fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable?
}
```

```kotlin
// Sketch: no reference code and no conformance claim.
class SuspendAdapter<Req, Res>(
    private val scope: CoroutineScope, // application-scoped
    private val send: suspend (Invocation<Req>) -> AdapterResult<Res>,
) : Adapter<Req, Res> {
    override fun execute(invocation: Invocation<Req>, done: (AdapterResult<Res>) -> Unit): Cancellable {
        val job = scope.launch(Dispatchers.IO) {
            val result = try {
                send(invocation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AdapterResult.Failed(e.toString()) // it may have left: the runtime classifies it
            }
            done(result)
        }
        return Cancellable { job.cancel() }
    }
}
```

`send` следует проверкам адаптера из [приложения D Core](../../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative):

- он передаёт `invocation.idempotencyKey` получателю, который принимает ключ, и никогда не придумывает ключ сам (C5);
- он не повторяет запросы, поэтому отключите перехватчики повторов (retry interceptors) в HTTP-клиенте (C2);
- он возвращает `NotApplied` только при наличии доказательства, например когда в соединении отказано до того, как что-либо было записано; исключение после того, как запрос мог быть записан, — это `Failed` (C3);
- время попытки он не отмеряет сам, это делает тайм-аут попытки из привязки; отменённая задача ничего не сообщает, и попытку завершает runtime.

### Состояние в Compose или во View

```kotlin
// Sketch: no reference code and no conformance claim.
fun <S> Runtime.stateFlow(ball: Ball<S, *, *>, key: String): StateFlow<Snapshot<S>> {
    val flow = MutableStateFlow(read(ball, key))
    watch(ball, key) { flow.value = it }
    return flow.asStateFlow()
}
```

Типизированный `watch` сначала выдаёт текущий снимок, а затем по одному снимку на каждую фиксацию (`RuntimeTest`: `a typed watch receives the current snapshot, then one for each commit`). Вызова, который удалял бы наблюдателя, нет, поэтому создавайте один flow на экземпляр на уровне приложения. Каждый наблюдаемый ключ держит своего слушателя всё время жизни runtime. Поэтому наблюдайте ограниченный набор ключей, например текущее оформление заказа (checkout), а не по ключу на каждую строку списка; остальные читайте при отрисовке. [Руководство по честному интерфейсу](honest-ui.md) сопоставляет состояние с тем, что показывает экран.

### Хранилище на Room

У `Store` есть `load`, `save(record)`, `save(record, incarnation)`, `ids`, `delete` и `nextIncarnation` ([`Store.kt`](../../reference/kotlin/ballarch-runtime/src/main/kotlin/ballarch/runtime/Store.kt)). Набросок хранилища на Room:

- Одна строка на экземпляр: `type`, `key`, `stateVersion` и blob, закодированный `RecordCodec`, например `JavaSerializationCodec`. Тип и ключ лежат вне blob, поэтому запись, которая больше не декодируется, всё равно можно перечислить и удалить.
- Каждый `save` заменяет одну строку в одной транзакции. `load` может выполняться в любом потоке, потому что его вызывает `Runtime.read`.
- Таблица из одной строки хранит последнюю инкарнацию, а `nextIncarnation` её увеличивает. `save(record, incarnation)` читает её в той же транзакции и бросает `FencedException`, если у пишущего более старая инкарнация (R16). Проверки на уровне строки недостаточно: заменённый runtime мог бы записать строку, которую новый ещё не записывал.

### Kotlin Multiplatform

`Intent` и `CallRef` реализуют `java.io.Serializable`, поэтому ядро — это JVM-код, а не библиотека Multiplatform. Общее ядро экспортировало бы сериализуемые состояние и сообщения, а runtime и адаптеры оставило бы каждой платформе. Так ядро Crux на Rust описывает эффекты как данные, которые выполняет нативная оболочка ([документация Crux](https://redbadger.github.io/crux/), дата обращения: 2026-10-27).

## 3. Swift и iOS

```swift
// Sketch: no reference code and no conformance claim.
protocol Ball {
    associatedtype State: Codable & Sendable
    associatedtype Request: Sendable
    associatedtype Reply: Sendable
    static func initial(key: String) -> State
    static func decide(_ state: State, _ request: Request, _ ctx: inout Context) -> Decision<State, Reply>
    static func complete(_ state: State, _ completion: Completion, _ ctx: inout Context) -> Step<State, Reply>
}
enum Outcome<Value> { case done(Value), notDone(NotDoneReason), unknown(UnknownReason) }
```

- Разбирайте `Outcome` оператором switch без `default:`, тогда пропущенный `.unknown` станет ошибкой компиляции.
- Шаги Ball интерфейса выполняйте в классе с `@MainActor`, шаги Durable-Ball — в `actor`. Каждый шаг держите синхронным, без `await` между чтением состояния и фиксацией. Функции, изолированные актором, реентерабельны: в каждой точке приостановки может выполниться другая работа, и предложение советует обновлять состояние синхронно ([SE-0306](https://github.com/apple/swift-evolution/blob/main/proposals/0306-actors.md), дата обращения: 2026-10-27). Если фиксация должна ждать (`await`) базу данных, runtime не начинает другой шаг этого экземпляра, пока она не завершится (R3).
- Адаптеры — `async`-функции в `Task`, каждая сообщает о результате один раз. `task.cancel()` просит адаптер остановиться; это не доказательство, поэтому исход оно никогда не определяет.
- Хранилище: SQLite, одна транзакция на фиксацию, отсечение по инкарнации, как выше. С файлами атомарного переименования недостаточно: выполните sync до отправки ([Core §6](../../spec/ballarch-core-2.0.md#6-profiles)).
- UI: копируйте снимки из чтений в модель `@Observable` на главном акторе. Фреймворку [Observation](https://developer.apple.com/documentation/observation) нужна iOS 17 или новее (дата обращения: 2026-10-27); на более ранних версиях используйте `ObservableObject`.
- Здесь не сопоставлены фоновые передачи `URLSession`.

## 4. Rust в стиле sans-IO

В стиле sans-IO протокол — это конечный автомат без ввода-вывода и без чтения часов. Цикл событий подаёт ему входы и время и выполняет то, что он просит ([Firezone 2024](https://www.firezone.dev/blog/sans-io), дата обращения: 2026-10-27).

```rust
// Sketch: no reference code and no conformance claim.
pub trait Ball {
    type State: Clone;
    type Request;
    type Reply;
    fn decide(s: &Self::State, r: &Self::Request, ctx: &mut Context) -> Decision<Self::State, Self::Reply>;
    fn complete(s: &Self::State, c: &Completion, ctx: &mut Context) -> Step<Self::State, Self::Reply>;
}
pub trait Driver { // driven by the host's event loop; nothing blocks
    fn handle_request(&mut self, now: Instant, to: BallId, msg: Message) -> RequestId;
    fn handle_report(&mut self, now: Instant, call: CallId, attempt: u32, report: Report);
    fn handle_timeout(&mut self, now: Instant);
    fn poll_commit(&mut self) -> Option<(u64, Record)>; // write durably, then ack_commit
    fn ack_commit(&mut self, seq: u64);
    fn poll_transmit(&mut self) -> Option<Transmit>; // attempts and cancels
    fn poll_timeout(&self) -> Option<Instant>;
}
```

- `poll_transmit` ничего не выдаёт для вызова, пока `ack_commit` не покрыл его решение (R5), а в профиле Durable — ещё и его упреждающую запись (R8).
- Взятая попытка — значение `Sending` с атрибутом `#[must_use]`, которое разрешает только `handle_report` или дедлайн в `handle_timeout`. `#[must_use]` лишь предупреждает, поэтому сброс (drop) такого значения не должен ничего решать. В асинхронном Rust сброшенный future отменяется, и его работа может потеряться ([Oxide RFD 400](https://rfd.shared.oxide.computer/rfd/400), дата обращения: 2026-10-27): сброшенная отправка — это попытка без отчёта, а не `NotDone`.
- `Cancel` — это выход `poll_transmit`; вызов всё равно завершается по таблице.
- На целевых платформах `no_std` используйте коллекции фиксированной ёмкости, например [`heapless`](https://docs.rs/heapless/latest/heapless/) (дата обращения: 2026-10-27), и выражайте ограничения в типах.
- Чистота: решатели живут в крейте, который зависит только от крейта ядра и собирается как `#![no_std]`, поэтому не может обратиться к `std::time`, `std::fs` или `std::net`. Это необходимо, но не достаточно: `static` с внутренней изменяемостью проходит.

## 5. TypeScript

```ts
// Sketch: no reference code and no conformance claim.
type Outcome<V> = { kind: "done"; value: V } | { kind: "notDone"; reason: string }
  | { kind: "unknown"; reason: string };
function assertNever(x: never): never { throw new Error(`unhandled: ${JSON.stringify(x)}`); }
```

- **Состояния** — размеченные объединения (discriminated unions). `switch`, который заканчивается на `default: return assertNever(x)`, не компилируется, если какой-то случай пропущен.
- **Планирование.** Ставьте входы в очередь отдельно для каждого экземпляра и разбирайте её из `queueMicrotask`, по одному шагу экземпляра за раз. Шаг никогда не делает `await`; если фиксация ждёт хранилище, экземпляр не делает других шагов, пока она не завершится (R3).
- **Хранилище в браузере.** Одна транзакция IndexedDB `readwrite` на фиксацию; отправка — после её события `complete`. Используйте `durability: "strict"`, когда фиксация должна сначала попасть в постоянное хранилище ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction), дата обращения: 2026-10-27).
- **Хранилище на сервере.** Postgres, одна транзакция на фиксацию. Таблица `owner` из одной строки хранит последнюю инкарнацию. Каждая фиксация выполняет `SELECT incarnation FROM owner FOR SHARE` и откатывается как отсечённая, если значение не её собственное. `nextIncarnation` — это `UPDATE owner SET incarnation = incarnation + 1 RETURNING incarnation`; такой запрос ждёт фиксаций, которые уже идут (R16).
- **React.** `useSyncExternalStore(subscribe, getSnapshot)` поверх чтения runtime; возвращайте один и тот же объект, пока не изменится ревизия.
- **Линтер чистоты.** В файлах `*.ball.ts` запретите `fetch`, `Date`, `Math.random`, таймеры и `crypto` правилами ESLint `no-restricted-globals` и `no-restricted-properties` ([ESLint](https://eslint.org/docs/latest/rules/no-restricted-globals), дата обращения: 2026-10-27). Это необходимо, но не достаточно.

HTTP-адаптер отправляет `Idempotency-Key: inv.idempotencyKey` и ограничивает попытку через `AbortSignal.timeout(ms)` или тайм-аутом попытки из привязки. Черновик IETF придаёт кодам 409 и 422 смысл, связанный с идемпотентностью, а его ответы об ошибках содержат тело или заголовок `Link`, который называет проблему ([draft-ietf-httpapi-idempotency-key-header-08](https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-08) §2.7, дата обращения: 2026-10-27). Другие серверы, в том числе построенные по [руководству по структуре](structure.md#коды-статуса-http), тоже используют 409 и 422, но для бизнес-отказов. Различайте их по телу ответа.

| Ответ | Отчёт | Почему |
|---|---|---|
| 2xx с читаемым телом | `Ok(value)` | получатель обработал запрос |
| 409, тело которого сообщает, что запрос с этим ключом ещё обрабатывается | `Failed` | это совет повторить, а не доказательство ([приложение D](../../spec/ballarch-core-2.0.md#appendix-d-adapter-checks-normative), C6) |
| 422, тело которого сообщает, что ключ повторно использован с другим запросом | `NotApplied`; запишите в лог дефект | ничего не применено; одно намерение назвали для двух разных запросов, или тело меняется от попытки к попытке |
| любой другой 4xx, который API документирует как «ничего не сделано», например отказ 409 или 422 от сервера, построенного по руководству по структуре | `Refused(value)` | отказ до какого-либо эффекта |
| 5xx, сетевая ошибка, прерывание (abort), тайм-аут | `Failed` | запрос мог быть применён; `fetch` не может сказать, ушёл ли он |

Cloudflare Durable Objects уже дают горизонт фиксации: выходной шлюз (output gate) объекта задерживает исходящие сообщения, пока идёт запись в хранилище, и отбрасывает их, если запись не удалась ([Varda 2021](https://blog.cloudflare.com/durable-objects-easy-fast-correct-choose-three/), дата обращения: 2026-10-27). Для классов эффекта, ключей, таблицы вызовов и классификации runtime всё равно нужен.

## 6. Прошивка на C

[Эталон на C](../../reference/c/README.md) выполняет по одному экземпляру Ball на задачу прошивки.

- Тип Ball — это `ba_ball`: таблица портов с классами эффекта и чистая функция `step` в собственной единице трансляции, которая включает только `ballarch.h`.
- Плата предоставляет `ba_platform`: `nvm_write` и `nvm_read` над двумя слотами записи, `transmit`, 64-битные часы в миллисекундах, которые никогда не переполняются, и необязательный хук трассировки.
- Runtime фиксирует состояние и журнал вызовов одной записью с проверкой CRC и записывает `Sending` перед каждой передачей.

Ключ вызова он передаёт как ключ идемпотентности; ключи повторяются, когда 32-битный ключ переполняется или слоты NVM стираются. В нём нет Cancel, намерений, окон дедупликации, тайм-аутов попытки и портов со сверкой, и он не выдаёт трассу соответствия ([Core §9.4](../../spec/ballarch-core-2.0.md#94-conformance-at-other-levels)). Драйверы, прерывания и DMA описаны в руководстве [по уровням](levels.md).

## 7. Как написать runtime на новом языке

1. **Начните с Transient.** Постройте части 1–8 и 10. Часть 9 и опции добавляйте, когда эти части пройдут проверки.
2. **Симулируйте получателей.** Привяжите каждый порт адаптера к симулированному получателю, который внедряет «судьбы» (fates) запросов из [приложения E](../../spec/ballarch-core-2.0.md#appendix-e-conformance-scenarios-normative) и записывает, что на самом деле произошло с каждым вызовом. Idempotent-получатель дедуплицирует по переданному ключу и помнит ключи после перезапуска ([как получить трассу](../../tools/TRACE-FORMAT.md#producing-a-trace)). В testkit на Kotlin это делают `Harness`, `SimPort` и `TraceJson`.
3. **Перебирайте.** Из S1–S8 прогоните каждый сценарий, в котором назван профиль, в этом профиле, если вы его заявляете, а S3 и S8, в которых профиль не назван, — в каждом заявленном профиле; S9–S12 прогоните для поддерживаемых опций, как их формулирует приложение E. Каждый прогон запишите как трассу. B1–B5 оформите как детерминированные тесты.
4. **Проверьте трассы.** Перебранные трассы должны проходить проверку, а трассы с внесёнными нарушениями — проваливать её ([как проверить трассу](../../tools/TRACE-FORMAT.md#checking-a-trace)):

   ```sh
   python3 tools/oracles.py path/to/traces/*.jsonl
   python3 tools/oracles.py --expect-violations path/to/seeded/*.jsonl
   ```

5. **Покройте то, чего трассы не показывают.** Трассы не показывают атомарную фиксацию (R4), ограничения (R11), заявления о профиле (R13), исключительное владение (R16), чтения и ключи идемпотентности ([Core §9.6](../../spec/ballarch-core-2.0.md#96-conformance-trace)). R4 показывает перебор падений, ограничения — граничные тесты, владение — S11, ключи — S4b. Заявлениям о профиле и чтениям нужны собственные тесты или ревью.
6. **Сопоставьте жизненный цикл** с переходами [модели Alloy](../../formal/alloy/README.md): commit, markSending, send, retry, receive, timeout, crash и recover (§9.1, пункт 3).

Такие доказательства относятся только к перебранным прогонам и не заменяют формального доказательства. Заявление называет профиль, runtime, сценарий и доказательства ([Core §9.5](../../spec/ballarch-core-2.0.md#95-claims)).
