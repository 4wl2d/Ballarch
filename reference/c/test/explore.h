/*
 * A bounded exhaustive explorer for one Ball on the firmware runtime, with a
 * simulated board: two NVM slots, a link whose fate is chosen per transmission,
 * a device model supplied by the test, and faults.
 *
 * Every run is a path in a tree of choices, explored depth first:
 *  - faults: at every power-loss point of a run (each main-loop wakeup, before
 *    and in the middle of each NVM write, and after the write-ahead record but
 *    before the bytes leave), the run either continues or loses power there;
 *    each NVM write may also fail with an error. The slots are erased flash
 *    (0xFF); a write torn by power loss leaves the slot erased except for its
 *    first 4 bytes (the magic word), its first 16 (the header) or its first
 *    half, each explored. At most EX_FAULTS faults per run. Points are chosen
 *    lazily, so every point of every explored run is tried and no run is
 *    repeated;
 *  - after a fault, the controller restarts after a short delay or after one
 *    longer than every deadline;
 *  - link fates: chosen for the first `fate_bound` transmissions of a run; later
 *    transmissions are answered in time (the count is reported).
 * A run that needs more than EX_MAX_DEPTH choices, EX_MAX_EVENTS link events or
 * EX_MAX_LOG call keys fails the exploration, and so does a main loop that
 * wakes EX_MAX_STALL times in a row without time advancing.
 */
#ifndef EXPLORE_H
#define EXPLORE_H

#include "ballarch_runtime.h"

#ifndef EX_FAULTS
#define EX_FAULTS 1 /* faults per run */
#endif
#ifndef EX_MAX_DEPTH
#define EX_MAX_DEPTH 1024 /* choices per run; a run that needs more fails */
#endif
#ifndef EX_MAX_EVENTS
#define EX_MAX_EVENTS 64 /* link events per run; a run that needs more fails */
#endif
#ifndef EX_MAX_LOG
#define EX_MAX_LOG 64 /* call keys per run; a run that needs more fails */
#endif
#define EX_MAX_STALL 16 /* main-loop wakeups in a row at one instant; more is a livelock */
#define EX_LATENCY_MS 40u
#define EX_IDLE_TICK_MS 100u /* the main loop also wakes when nothing is due */
#define EX_REBOOT_MS 250u
#define EX_LONG_REBOOT_MS 2500u /* longer than every deadline of the examples */
#define EX_K_APPLY 99           /* not a report: a delayed request reaches the device */

/* What the test knows about one call key. */
typedef struct {
    uint32_t key;
    uint8_t port;
    int committed, completed, transmissions;
    int applied; /* ground truth: the device applied the request */
    int timer;
    uint64_t due; /* timers: commit time plus delay */
    ba_outcome outcome;
} ex_call;

typedef struct {
    uint64_t at;
    uint32_t key;
    uint8_t attempt, kind;
    int32_t value;
    int incarnation, live;
} ex_event;

typedef struct {
    const char *name;
    const ba_ball *ball;
    const uint8_t *budget; /* attempts per port, as passed to ba_boot */
    int fate_bound;        /* link fates are explored for the first fate_bound transmissions of a run */
    uint64_t start_ms, end_ms;
    const char *const *finals; /* names of the final classes that check() tallies */
    int n_finals;
    void (*reset)(void);                 /* forget the device model's state */
    void (*start)(ba_runtime *rt);       /* requests at the first power-on */
    void (*transmit)(ex_call *c, uint8_t port, uint32_t key, int32_t arg, uint8_t attempt); /* the device and link */
    void (*apply)(ex_call *c, const ex_event *e); /* a delayed request reaches the device */
    void (*committed)(const ba_record *r);        /* a record became durable; may be NULL */
    int (*check)(const ba_runtime *rt);           /* oracles at quiescence; returns the final class */
    long expect_runs; /* if nonzero, an exploration without a violation must make exactly this many runs */
} ex_model;

/* Shared with the device models. */
uint64_t ex_now(void);
int ex_choose(int n);                       /* an explored choice among n */
int ex_fate(int n);                         /* the fate of one transmission among n; 0 is "answered in time" */
void ex_violation(const char *tag, const char *fmt, ...);
void ex_schedule(uint64_t delay, uint32_t key, uint8_t attempt, int kind, int32_t value);
ex_call *ex_log(uint32_t key);
const ba_record *ex_durable(void);          /* the newest valid record, as the next boot would see it */

/* Explores every run of the model. Prints a summary; returns 0 if no run violated an oracle and the run
   total is the expected one. */
int ex_explore(const ex_model *m);

#endif
