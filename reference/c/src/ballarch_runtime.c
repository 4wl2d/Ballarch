/*
 * The Ballarch Core 2 firmware runtime. It enforces the runtime rules for one
 * Ball instance: serial steps (R3: one call into the Ball at a time, from the
 * main loop), atomic commit (R4: one NVM record write), commit before dispatch
 * (R5), one completion per call (R6), outcomes by phase and by the effect class recorded at commit (R7),
 * no re-send of non-idempotent calls and write-ahead before every send (R8),
 * and the fixed bounds of ballarch.h (R11).
 *
 * Seeded bugs for the negative controls in the Makefile (never enabled in a build):
 *   BA_MUTANT_NO_WRITE_AHEAD          transmits before recording the call as Sending
 *   BA_MUTANT_NO_WRITE_AHEAD_ON_RETRY records the write-ahead for the first attempt only
 *   BA_MUTANT_RESEND_AFTER_CRASH      re-sends a possibly-sent non-idempotent call at boot
 *   BA_MUTANT_EARLY_TIMER             fires timers without waiting for their due time
 *   BA_MUTANT_32BIT_TIME              keeps time and deadlines in 32 bits, which wrap
 *   BA_MUTANT_NACK_ALWAYS_CONCLUSIVE  takes a NACK of a retry as proof that no attempt applied
 *   BA_MUTANT_IGNORE_NVM_ERROR        transmits although the write-ahead record was not stored
 *   BA_MUTANT_PREFLIGHT=n             disables preflight check n (1..8)
 *   BA_MUTANT_UNBOUNDED_KEYS          accepts a decision that drew more than BA_MAX_KEYS keys
 *   BA_MUTANT_NO_QUARANTINE           keeps running after refusing a completion
 *   BA_MUTANT_STATE_IN_PLACE          passes the committed state itself to the decider
 *   BA_MUTANT_NO_BALL_CHECK           runs a Ball that does not fit the fixed record
 *   BA_MUTANT_FRESH_ON_CORRUPT        starts fresh when both committed slots are corrupt
 *   BA_MUTANT_IGNORE_LAYOUT           loads a record written for another layout or Ball type
 *   BA_MUTANT_NO_HEAD_CRC             takes a slot whose magic matches for another build's record, without its header CRC
 *   BA_MUTANT_QUARANTINE_ON_TORN      quarantines when one slot holds a torn first write and the other was never written
 *   BA_MUTANT_ONE_SLOT                writes every record to the same slot, so a torn write destroys the last commit (R4)
 *   BA_MUTANT_TRACE_BEFORE_COMMIT     reports a call as committed before its record is written
 *   BA_MUTANT_KEEP_CLOSED_CALL        leaves a completed call in the ledger, where its deadline completes it again
 *   BA_MUTANT_EARLY_WAKEUP            asks to be woken before a timer can fire, so the main loop spins without work
 *   BA_MUTANT_TIMEOUT_AS_DONE         ends a call whose deadline passed after sending Done, not Unknown
 */
#include "ballarch_runtime.h"

#include <string.h>

/*
 * Record layout 3: 64-bit deadlines, a layout fingerprint and a header CRC.
 * Layout 1 (0xBA11A2C4) had no fingerprint; layout 2 (0xBA11A2C5) was never released.
 */
#define BA_MAGIC 0xBA11A2C6u
#define BA_HEAD_BYTES offsetof(ba_record, head_crc) /* magic, layout, seq */

#ifdef BA_MUTANT_IGNORE_NVM_ERROR
#define STORED(status) ((void)(status), 1)
#else
#define STORED(status) ((status) == 0)
#endif

#ifdef BA_MUTANT_PREFLIGHT
#define CHECK(n, failed) ((n) != (BA_MUTANT_PREFLIGHT) && (failed))
#else
#define CHECK(n, failed) (failed)
#endif

/* CRC-32 (IEEE 802.3, reflected), one byte at a time from a 1 KiB table in flash. */
static const uint32_t CRC_TABLE[256] = {
    0x00000000u, 0x77073096u, 0xEE0E612Cu, 0x990951BAu, 0x076DC419u, 0x706AF48Fu, 0xE963A535u, 0x9E6495A3u,
    0x0EDB8832u, 0x79DCB8A4u, 0xE0D5E91Eu, 0x97D2D988u, 0x09B64C2Bu, 0x7EB17CBDu, 0xE7B82D07u, 0x90BF1D91u,
    0x1DB71064u, 0x6AB020F2u, 0xF3B97148u, 0x84BE41DEu, 0x1ADAD47Du, 0x6DDDE4EBu, 0xF4D4B551u, 0x83D385C7u,
    0x136C9856u, 0x646BA8C0u, 0xFD62F97Au, 0x8A65C9ECu, 0x14015C4Fu, 0x63066CD9u, 0xFA0F3D63u, 0x8D080DF5u,
    0x3B6E20C8u, 0x4C69105Eu, 0xD56041E4u, 0xA2677172u, 0x3C03E4D1u, 0x4B04D447u, 0xD20D85FDu, 0xA50AB56Bu,
    0x35B5A8FAu, 0x42B2986Cu, 0xDBBBC9D6u, 0xACBCF940u, 0x32D86CE3u, 0x45DF5C75u, 0xDCD60DCFu, 0xABD13D59u,
    0x26D930ACu, 0x51DE003Au, 0xC8D75180u, 0xBFD06116u, 0x21B4F4B5u, 0x56B3C423u, 0xCFBA9599u, 0xB8BDA50Fu,
    0x2802B89Eu, 0x5F058808u, 0xC60CD9B2u, 0xB10BE924u, 0x2F6F7C87u, 0x58684C11u, 0xC1611DABu, 0xB6662D3Du,
    0x76DC4190u, 0x01DB7106u, 0x98D220BCu, 0xEFD5102Au, 0x71B18589u, 0x06B6B51Fu, 0x9FBFE4A5u, 0xE8B8D433u,
    0x7807C9A2u, 0x0F00F934u, 0x9609A88Eu, 0xE10E9818u, 0x7F6A0DBBu, 0x086D3D2Du, 0x91646C97u, 0xE6635C01u,
    0x6B6B51F4u, 0x1C6C6162u, 0x856530D8u, 0xF262004Eu, 0x6C0695EDu, 0x1B01A57Bu, 0x8208F4C1u, 0xF50FC457u,
    0x65B0D9C6u, 0x12B7E950u, 0x8BBEB8EAu, 0xFCB9887Cu, 0x62DD1DDFu, 0x15DA2D49u, 0x8CD37CF3u, 0xFBD44C65u,
    0x4DB26158u, 0x3AB551CEu, 0xA3BC0074u, 0xD4BB30E2u, 0x4ADFA541u, 0x3DD895D7u, 0xA4D1C46Du, 0xD3D6F4FBu,
    0x4369E96Au, 0x346ED9FCu, 0xAD678846u, 0xDA60B8D0u, 0x44042D73u, 0x33031DE5u, 0xAA0A4C5Fu, 0xDD0D7CC9u,
    0x5005713Cu, 0x270241AAu, 0xBE0B1010u, 0xC90C2086u, 0x5768B525u, 0x206F85B3u, 0xB966D409u, 0xCE61E49Fu,
    0x5EDEF90Eu, 0x29D9C998u, 0xB0D09822u, 0xC7D7A8B4u, 0x59B33D17u, 0x2EB40D81u, 0xB7BD5C3Bu, 0xC0BA6CADu,
    0xEDB88320u, 0x9ABFB3B6u, 0x03B6E20Cu, 0x74B1D29Au, 0xEAD54739u, 0x9DD277AFu, 0x04DB2615u, 0x73DC1683u,
    0xE3630B12u, 0x94643B84u, 0x0D6D6A3Eu, 0x7A6A5AA8u, 0xE40ECF0Bu, 0x9309FF9Du, 0x0A00AE27u, 0x7D079EB1u,
    0xF00F9344u, 0x8708A3D2u, 0x1E01F268u, 0x6906C2FEu, 0xF762575Du, 0x806567CBu, 0x196C3671u, 0x6E6B06E7u,
    0xFED41B76u, 0x89D32BE0u, 0x10DA7A5Au, 0x67DD4ACCu, 0xF9B9DF6Fu, 0x8EBEEFF9u, 0x17B7BE43u, 0x60B08ED5u,
    0xD6D6A3E8u, 0xA1D1937Eu, 0x38D8C2C4u, 0x4FDFF252u, 0xD1BB67F1u, 0xA6BC5767u, 0x3FB506DDu, 0x48B2364Bu,
    0xD80D2BDAu, 0xAF0A1B4Cu, 0x36034AF6u, 0x41047A60u, 0xDF60EFC3u, 0xA867DF55u, 0x316E8EEFu, 0x4669BE79u,
    0xCB61B38Cu, 0xBC66831Au, 0x256FD2A0u, 0x5268E236u, 0xCC0C7795u, 0xBB0B4703u, 0x220216B9u, 0x5505262Fu,
    0xC5BA3BBEu, 0xB2BD0B28u, 0x2BB45A92u, 0x5CB36A04u, 0xC2D7FFA7u, 0xB5D0CF31u, 0x2CD99E8Bu, 0x5BDEAE1Du,
    0x9B64C2B0u, 0xEC63F226u, 0x756AA39Cu, 0x026D930Au, 0x9C0906A9u, 0xEB0E363Fu, 0x72076785u, 0x05005713u,
    0x95BF4A82u, 0xE2B87A14u, 0x7BB12BAEu, 0x0CB61B38u, 0x92D28E9Bu, 0xE5D5BE0Du, 0x7CDCEFB7u, 0x0BDBDF21u,
    0x86D3D2D4u, 0xF1D4E242u, 0x68DDB3F8u, 0x1FDA836Eu, 0x81BE16CDu, 0xF6B9265Bu, 0x6FB077E1u, 0x18B74777u,
    0x88085AE6u, 0xFF0F6A70u, 0x66063BCAu, 0x11010B5Cu, 0x8F659EFFu, 0xF862AE69u, 0x616BFFD3u, 0x166CCF45u,
    0xA00AE278u, 0xD70DD2EEu, 0x4E048354u, 0x3903B3C2u, 0xA7672661u, 0xD06016F7u, 0x4969474Du, 0x3E6E77DBu,
    0xAED16A4Au, 0xD9D65ADCu, 0x40DF0B66u, 0x37D83BF0u, 0xA9BCAE53u, 0xDEBB9EC5u, 0x47B2CF7Fu, 0x30B5FFE9u,
    0xBDBDF21Cu, 0xCABAC28Au, 0x53B39330u, 0x24B4A3A6u, 0xBAD03605u, 0xCDD70693u, 0x54DE5729u, 0x23D967BFu,
    0xB3667A2Eu, 0xC4614AB8u, 0x5D681B02u, 0x2A6F2B94u, 0xB40BBE37u, 0xC30C8EA1u, 0x5A05DF1Bu, 0x2D02EF8Du,
};

static uint32_t crc_update(uint32_t crc, const void *data, size_t n)
{
    const uint8_t *p = data;
    for (size_t i = 0; i < n; i++) crc = (crc >> 8) ^ CRC_TABLE[(crc ^ p[i]) & 0xFFu];
    return crc;
}

uint32_t ba_crc32(const void *data, size_t n) { return ~crc_update(0xFFFFFFFFu, data, n); }

uint32_t ba_layout(const ba_ball *ball)
{
    uint32_t f[5] = {(uint32_t)sizeof(ba_record), BA_MAX_CALLS, BA_MAX_STATE, (uint32_t)ball->state_size, ball->schema};
    uint32_t crc = crc_update(0xFFFFFFFFu, f, sizeof f);
    if (ball->type) crc = crc_update(crc, ball->type, strlen(ball->type));
    return ~crc;
}

static void trace(const ba_runtime *rt, int event, uint32_t key, const ba_outcome *o)
{
    if (rt->pf->trace) rt->pf->trace(rt->pf->user, event, key, o);
}

static uint64_t now(const ba_runtime *rt)
{
#ifdef BA_MUTANT_32BIT_TIME
    return (uint32_t)rt->pf->now(rt->pf->user);
#else
    return rt->pf->now(rt->pf->user);
#endif
}

static uint64_t deadline_after(uint64_t t, uint32_t timeout_ms)
{
#ifdef BA_MUTANT_32BIT_TIME
    return (uint32_t)(t + timeout_ms);
#else
    return t + timeout_ms; /* cannot overflow: timeout_ms <= BA_MAX_TIMEOUT_MS */
#endif
}

static ba_outcome outcome(ba_kind kind, int32_t value)
{
    ba_outcome o = {kind, value};
    return o;
}

static ba_effect effect_of(const ba_runtime *rt, uint8_t port)
{
    return port == BA_TIMER_PORT ? BA_SAFE : rt->ball->effects[port];
}

/*
 * The effect class a call was committed with, kept in the low byte of its record's `reserved`
 * field as the class plus 1. A call of a record written before the class was recorded has 0
 * there and is classified by the current port table.
 */
static ba_effect call_effect(const ba_runtime *rt, const ba_call_rec *c)
{
    uint8_t recorded = (uint8_t)(c->reserved & 0xFFu);
    return recorded != 0 ? (ba_effect)(recorded - 1u) : effect_of(rt, c->port);
}

static uint8_t attempts_allowed(const ba_runtime *rt, uint8_t port)
{
    if (effect_of(rt, port) == BA_NON_IDEMPOTENT || rt->max_attempts == NULL || rt->max_attempts[port] == 0) return 1;
    return rt->max_attempts[port];
}

static void quarantine(ba_runtime *rt, uint32_t key)
{
    rt->quarantined = 1;
    trace(rt, BA_T_FAULT, key, NULL);
}

/* The atomic commit point: the whole record goes to the slot after the current one. 0 if stored. */
static int persist(ba_runtime *rt)
{
    rt->rec.magic = BA_MAGIC;
    rt->rec.layout = rt->layout;
    rt->rec.seq++;
    rt->rec.head_crc = ba_crc32(&rt->rec, BA_HEAD_BYTES);
    rt->rec.crc = ba_crc32(&rt->rec, offsetof(ba_record, crc));
#ifdef BA_MUTANT_ONE_SLOT
    return rt->pf->nvm_write(rt->pf->user, 0, &rt->rec, sizeof rt->rec);
#else
    return rt->pf->nvm_write(rt->pf->user, (int)(rt->rec.seq & 1u), &rt->rec, sizeof rt->rec);
#endif
}

/* A whole header, of any record layout: the magic and the header CRC match. */
static int header_valid(const ba_record *r) { return r->magic == BA_MAGIC && r->head_crc == ba_crc32(r, BA_HEAD_BYTES); }

/* A record this build wrote for this Ball: header, layout and CRC match. */
static int valid(const ba_runtime *rt, const ba_record *r)
{
#ifdef BA_MUTANT_IGNORE_LAYOUT
    (void)rt;
    return header_valid(r) && r->crc == ba_crc32(r, offsetof(ba_record, crc));
#else
    return header_valid(r) && r->layout == rt->layout && r->crc == ba_crc32(r, offsetof(ba_record, crc));
#endif
}

#ifndef BA_MUTANT_IGNORE_LAYOUT
/* A record written by a build with another layout or Ball type. Only its header can be checked. */
static int foreign(const ba_runtime *rt, const ba_record *r)
{
#ifdef BA_MUTANT_NO_HEAD_CRC
    return r->magic == BA_MAGIC && r->layout != rt->layout;
#else
    return header_valid(r) && r->layout != rt->layout;
#endif
}
#endif

/* 1: a committed record was loaded; 0: nothing was ever committed; -1: a committed record cannot be used. */
static int load(ba_runtime *rt)
{
    ba_record r[2];
    int written[2];
    const ba_record *best = NULL;
    for (int s = 0; s < 2; s++) {
        written[s] = rt->pf->nvm_read(rt->pf->user, s, &r[s], sizeof r[s]);
        if (written[s] && valid(rt, &r[s]) && (best == NULL || r[s].seq > best->seq)) best = &r[s];
    }
#ifndef BA_MUTANT_IGNORE_LAYOUT
    /* A record of another layout or Ball type, newer than every record of ours: its ledger may hold possibly-sent calls. */
    for (int s = 0; s < 2; s++)
        if (written[s] && foreign(rt, &r[s]) && (best == NULL || r[s].seq > best->seq)) return -1;
#endif
    if (best != NULL) {
        rt->rec = *best;
        return 1;
    }
#if defined(BA_MUTANT_FRESH_ON_CORRUPT)
    return 0;
#elif defined(BA_MUTANT_QUARANTINE_ON_TORN)
    return written[0] || written[1] ? -1 : 0;
#else
    return written[0] && written[1] ? -1 : 0; /* both written and none valid is corruption; one torn first write is not */
#endif
}

static int slot_of(const ba_runtime *rt, uint32_t key)
{
    for (unsigned i = 0; i < BA_MAX_CALLS; i++)
        if (rt->rec.calls[i].used && rt->rec.calls[i].key == key) return (int)i;
    return -1;
}

static int is_queued(const ba_runtime *rt, uint32_t key)
{
    for (unsigned i = 0; i < rt->queued; i++)
        if (rt->queue[i].key == key) return 1;
    return 0;
}

/* Chooses the single outcome of a call; the Ball commits it later in drain() (R6). */
static void resolve(ba_runtime *rt, int i, ba_outcome o)
{
    const ba_call_rec *c = &rt->rec.calls[i];
    if (is_queued(rt, c->key) || rt->queued >= BA_MAX_CALLS) return;
    ba_resolution r = {c->key, c->port, o};
    rt->queue[rt->queued++] = r;
    rt->current_attempt[i] = 0;
}

static void send(ba_runtime *rt, int i)
{
    ba_call_rec *c = &rt->rec.calls[i];
    if (c->port == BA_TIMER_PORT || rt->quarantined) return; /* timers are kept by the runtime itself */
    c->phase = BA_SENDING;
    c->attempts++;
#ifdef BA_MUTANT_NO_WRITE_AHEAD
    rt->current_attempt[i] = c->attempts;
    trace(rt, BA_T_SENT, c->key, NULL);
    rt->pf->transmit(rt->pf->user, c->port, c->key, c->arg, c->attempts);
    (void)persist(rt);
#else
#ifdef BA_MUTANT_NO_WRITE_AHEAD_ON_RETRY
    int status = c->attempts == 1 ? persist(rt) : 0;
#else
    int status = persist(rt); /* write-ahead: NVM says "possibly sent" before anything leaves (R8) */
#endif
    if (!STORED(status)) {
        quarantine(rt, c->key); /* nothing leaves without a durable write-ahead record */
        return;
    }
    rt->current_attempt[i] = c->attempts;
    trace(rt, BA_T_SENT, c->key, NULL);
    rt->pf->transmit(rt->pf->user, c->port, c->key, c->arg, c->attempts);
#endif
}

static void fail_attempt(ba_runtime *rt, int i)
{
    const ba_call_rec *c = &rt->rec.calls[i];
    ba_effect e = call_effect(rt, c);
    if (e != BA_NON_IDEMPOTENT && c->attempts < attempts_allowed(rt, c->port) && now(rt) < c->deadline) {
        send(rt, i);
        return;
    }
    if (e == BA_SAFE) resolve(rt, i, outcome(BA_NOT_DONE, BA_R_NO_ANSWER));
    else resolve(rt, i, outcome(BA_UNKNOWN, c->attempts > 1 ? BA_R_RETRIES_EXHAUSTED : BA_R_LOST_AFTER_SEND));
}

/*
 * Preflight (Core §3.5): at most BA_MAX_OUTPUTS calls and BA_MAX_CALLS open
 * calls, known ports, timeouts in (0, BA_MAX_TIMEOUT_MS], timers made with
 * ba_timer, unique keys. The number of keys drawn is checked in step().
 */
static int preflight(const ba_runtime *rt, const ba_outputs *out, int closing)
{
    if (CHECK(1, out->n > BA_MAX_OUTPUTS)) return 0;
    unsigned open = 0;
    for (unsigned i = 0; i < BA_MAX_CALLS; i++)
        if (rt->rec.calls[i].used && (int)i != closing) open++;
    if (CHECK(2, open + out->n > BA_MAX_CALLS)) return 0;
    for (unsigned k = 0; k < out->n && k < BA_MAX_OUTPUTS; k++) {
        const ba_call *c = &out->call[k];
        if (CHECK(3, c->port >= rt->ball->n_ports)) return 0;
        if (CHECK(4, c->timeout_ms == 0)) return 0;
        if (CHECK(5, c->timeout_ms > BA_MAX_TIMEOUT_MS)) return 0;
        if (CHECK(6, c->port == BA_TIMER_PORT && (c->arg < 0 || c->timeout_ms != (uint32_t)c->arg + BA_TIMER_MARGIN_MS)))
            return 0;
        int existing = slot_of(rt, c->key);
        if (CHECK(7, existing >= 0 && existing != closing)) return 0;
        for (unsigned m = 0; m < k; m++)
            if (CHECK(8, out->call[m].key == c->key)) return 0;
    }
    return 1;
}

static int too_many_keys(const ba_ctx *ctx)
{
#ifdef BA_MUTANT_UNBOUNDED_KEYS
    (void)ctx;
    return 0;
#else
    return ctx->keys_issued > BA_MAX_KEYS;
#endif
}

/*
 * One step: run the Ball, check its decision, commit state and ledger in one
 * record write, then dispatch. Returns 1 (committed), 0 (rejected) or -1 (refused or fault).
 */
static int step(ba_runtime *rt, const ba_input *in, int closing, int32_t *reply)
{
    uint8_t cur[BA_MAX_STATE], next[BA_MAX_STATE];
    ba_outputs out;
    int32_t rep = 0;
    memcpy(cur, rt->rec.state, sizeof cur);
    memset(next, 0, sizeof next);
    memset(&out, 0, sizeof out);
    uint64_t t = now(rt);
    ba_ctx ctx = {t, rt->rec.revision + 1u, 0};
#ifdef BA_MUTANT_STATE_IN_PLACE
    ba_verdict v = rt->ball->step(rt->rec.state, in, &ctx, next, &out, &rep);
#else
    ba_verdict v = rt->ball->step(cur, in, &ctx, next, &out, &rep); /* a copy: the committed record stays out of reach */
#endif
    if (reply) *reply = rep;
    int completion = in->kind == BA_IN_COMPLETION;
    if (v == BA_REJECT && !completion) return 0;
    if (v == BA_REJECT || too_many_keys(&ctx) || !preflight(rt, &out, closing)) {
#ifndef BA_MUTANT_NO_QUARANTINE
        if (completion) quarantine(rt, in->key); /* an obligation the instance holds cannot be refused */
#endif
        return -1;
    }
    memcpy(rt->rec.state, next, rt->ball->state_size);
    rt->rec.revision++;
#ifndef BA_MUTANT_KEEP_CLOSED_CALL
    if (closing >= 0) { /* the completed call leaves the ledger in the same record that commits its completion (R6) */
        memset(&rt->rec.calls[closing], 0, sizeof rt->rec.calls[closing]);
        rt->current_attempt[closing] = 0;
    }
#endif
    int fresh[BA_MAX_OUTPUTS];
    unsigned n = 0;
    for (unsigned k = 0; k < out.n && k < BA_MAX_OUTPUTS; k++) { /* bounded even if preflight were wrong */
        unsigned j = 0;
        while (j < BA_MAX_CALLS && rt->rec.calls[j].used) j++;
        if (j == BA_MAX_CALLS) break;
        uint8_t port = out.call[k].port;
        ba_call_rec c = {deadline_after(t, out.call[k].timeout_ms), out.call[k].key, out.call[k].arg, 1, port, BA_PENDING, 0,
                         (uint32_t)effect_of(rt, port) + 1u}; /* the call's class for its whole life (Core §3.2) */
        rt->rec.calls[j] = c;
        fresh[n++] = (int)j;
    }
#ifdef BA_MUTANT_TRACE_BEFORE_COMMIT
    for (unsigned k = 0; k < n; k++) trace(rt, BA_T_CALL_COMMITTED, rt->rec.calls[fresh[k]].key, NULL);
#endif
    if (!STORED(persist(rt))) { /* the commit point (R4) */
        quarantine(rt, completion ? in->key : 0); /* RAM no longer matches NVM: stop; the next boot reloads */
        return -1;
    }
    if (completion) trace(rt, BA_T_COMPLETED, in->key, &in->outcome);
#ifndef BA_MUTANT_TRACE_BEFORE_COMMIT
    for (unsigned k = 0; k < n; k++) trace(rt, BA_T_CALL_COMMITTED, rt->rec.calls[fresh[k]].key, NULL);
#endif
    for (unsigned k = 0; k < n; k++) send(rt, fresh[k]); /* dispatch strictly after the commit (R5) */
    return 1;
}

static void drain(ba_runtime *rt)
{
    while (rt->queued > 0 && !rt->quarantined) {
        ba_resolution r = rt->queue[0];
        rt->queued--;
        memmove(&rt->queue[0], &rt->queue[1], rt->queued * sizeof rt->queue[0]);
        int i = slot_of(rt, r.key);
        if (i < 0) continue;
        ba_input in = {BA_IN_COMPLETION, 0, 0, r.port, r.key, r.outcome};
        step(rt, &in, i, NULL);
    }
}

void ba_boot(ba_runtime *rt, const ba_ball *ball, const ba_platform *pf, const uint8_t *max_attempts)
{
    memset(rt, 0, sizeof *rt);
    rt->ball = ball;
    rt->pf = pf;
    rt->max_attempts = max_attempts;
#ifndef BA_MUTANT_NO_BALL_CHECK
    if (ball->state_size > BA_MAX_STATE || ball->n_ports == 0 || ball->n_ports > BA_MAX_PORTS ||
        (ball->n_ports > 1 && ball->effects == NULL) || ball->initial == NULL || ball->step == NULL) {
        quarantine(rt, 0); /* a Ball that does not fit the fixed record is a configuration fault (R11) */
        return;
    }
#endif
    rt->layout = ba_layout(ball);
    int loaded = load(rt);
    if (loaded < 0) {
        quarantine(rt, 0); /* a committed ledger may hold possibly-sent calls: never forget it silently */
        return;
    }
    if (loaded == 0) {
        memset(&rt->rec, 0, sizeof rt->rec);
        ball->initial(rt->rec.state);
        return;
    }
    uint64_t t = now(rt);
    for (unsigned u = 0; u < BA_MAX_CALLS; u++) {
        int i = (int)u;
        const ba_call_rec *c = &rt->rec.calls[i];
        if (!c->used) continue;
        ba_effect e = call_effect(rt, c);
        if (c->port == BA_TIMER_PORT) { /* re-armed to its due time unless its deadline has passed */
            if (t >= c->deadline) resolve(rt, i, outcome(BA_NOT_DONE, BA_R_NO_ANSWER));
        } else if (t >= c->deadline) {
            if (c->phase == BA_PENDING) resolve(rt, i, outcome(BA_NOT_DONE, BA_R_NEVER_SENT));
            else if (e == BA_SAFE) resolve(rt, i, outcome(BA_NOT_DONE, BA_R_NO_ANSWER));
            else resolve(rt, i, outcome(BA_UNKNOWN, BA_R_DEADLINE_AFTER_SEND));
        } else if (c->phase == BA_PENDING) {
            send(rt, i);
        } else if (e == BA_NON_IDEMPOTENT) {
#ifdef BA_MUTANT_RESEND_AFTER_CRASH
            send(rt, i);
#else
            resolve(rt, i, outcome(BA_UNKNOWN, BA_R_RECOVERED_AFTER_CRASH));
#endif
        } else if (c->attempts < attempts_allowed(rt, c->port)) {
            send(rt, i);
        } else {
            resolve(rt, i, e == BA_SAFE ? outcome(BA_NOT_DONE, BA_R_NO_ANSWER) : outcome(BA_UNKNOWN, BA_R_RETRIES_EXHAUSTED));
        }
    }
    drain(rt);
}

int ba_request(ba_runtime *rt, int32_t request, int32_t arg, int32_t *reply)
{
    if (rt->quarantined) return -1;
    ba_input in = {BA_IN_REQUEST, request, arg, 0, 0, {BA_DONE, 0}};
    int r = step(rt, &in, -1, reply);
    drain(rt);
    return r;
}

void ba_report(ba_runtime *rt, uint32_t key, uint8_t attempt, ba_report_kind kind, int32_t value)
{
    int i = slot_of(rt, key);
    if (rt->quarantined || i < 0 || is_queued(rt, key) || rt->rec.calls[i].phase != BA_SENDING) {
        trace(rt, BA_T_LATE_REPORT, key, NULL);
        return;
    }
    const ba_call_rec *c = &rt->rec.calls[i];
    int current = rt->current_attempt[i] == attempt;
    /* Evidence of non-application covers only its attempt: conclusive if no other attempt can have been delivered. */
#ifdef BA_MUTANT_NACK_ALWAYS_CONCLUSIVE
    int conclusive = 1;
    (void)c;
#else
    int conclusive = c->attempts == 1 || call_effect(rt, c) == BA_SAFE;
#endif
    switch (kind) {
    case BA_REPORT_OK:
        resolve(rt, i, outcome(BA_DONE, value));
        break;
    case BA_REPORT_REFUSED:
    case BA_REPORT_NOT_APPLIED:
        if (conclusive) resolve(rt, i, outcome(BA_NOT_DONE, kind == BA_REPORT_REFUSED ? BA_R_REFUSED : BA_R_NOT_APPLIED));
        else if (current) fail_attempt(rt, i);
        break;
    case BA_REPORT_FAILED:
        if (current) fail_attempt(rt, i);
        break;
    }
    drain(rt);
}

void ba_tick(ba_runtime *rt)
{
    if (rt->quarantined) return;
    uint64_t t = now(rt);
    for (unsigned u = 0; u < BA_MAX_CALLS; u++) {
        int i = (int)u;
        const ba_call_rec *c = &rt->rec.calls[i];
        if (!c->used || is_queued(rt, c->key)) continue;
        if (c->port == BA_TIMER_PORT) {
#ifdef BA_MUTANT_EARLY_TIMER
            resolve(rt, i, outcome(BA_DONE, 0));
#else
            if (t + BA_TIMER_MARGIN_MS >= c->deadline) resolve(rt, i, outcome(BA_DONE, 0));
#endif
        } else if (t >= c->deadline) {
            if (c->phase == BA_PENDING) resolve(rt, i, outcome(BA_NOT_DONE, BA_R_NEVER_SENT));
            else if (call_effect(rt, c) == BA_SAFE) resolve(rt, i, outcome(BA_NOT_DONE, BA_R_NO_ANSWER));
#ifdef BA_MUTANT_TIMEOUT_AS_DONE
            else resolve(rt, i, outcome(BA_DONE, 0));
#else
            else resolve(rt, i, outcome(BA_UNKNOWN, BA_R_DEADLINE_AFTER_SEND));
#endif
        }
    }
    drain(rt);
}

uint64_t ba_next_wakeup(const ba_runtime *rt)
{
    if (rt->quarantined) return UINT64_MAX;
    if (rt->queued > 0) return now(rt);
    uint64_t next = UINT64_MAX;
    for (unsigned i = 0; i < BA_MAX_CALLS; i++) {
        const ba_call_rec *c = &rt->rec.calls[i];
        if (!c->used) continue;
#ifdef BA_MUTANT_EARLY_WAKEUP
        uint64_t at = c->port == BA_TIMER_PORT ? c->deadline - 2u * BA_TIMER_MARGIN_MS : c->deadline;
#else
        uint64_t at = c->port == BA_TIMER_PORT ? c->deadline - BA_TIMER_MARGIN_MS : c->deadline;
#endif
        if (at < next) next = at;
    }
    return next;
}

const void *ba_state(const ba_runtime *rt) { return rt->rec.state; }
