/*
 * Ballarch Core 2 — abstract model of the call-obligation protocol.
 *
 * Scope: one caller Ball whose runtime commits decisions durably
 * (Durable profile), a lossy/duplicating network, and one destination.
 * The model abstracts away business state: a "call" is created by a
 * committed decision and must receive exactly one terminal outcome.
 *
 * What is checked (bounded, not a proof for all sizes):
 *   P1 NoSendBeforeCommit      no request leaves before its decision commits
 *   P2 OutcomeStable           a terminal outcome, once applied, never changes
 *   P3 NoFalseNotDone          NotDone for an effectful call implies the effect never happened
 *   P4 AtMostOnceNonIdempotent the runtime never sends a non-idempotent request twice
 *   P5 DoneIsReal              Done is reported only after the destination processed the request
 *   P6 AtomicAcceptance        the caller's committed state and its obligation ledger agree
 *   L1 EveryObligationCloses   under fairness, every awaited call eventually gets an outcome
 *
 * Each Mutant switches on one plausible runtime bug. For every mutant
 * the model contains a command that is expected to FIND a counterexample
 * (expect 1); for the correct runtime every property is expected to hold
 * (expect 0) within the stated bounds. `run.py` checks those expectations.
 */
module obligations

abstract sig Kind {}
/* Effect classes of a port (RFC 9110 vocabulary): safe requests have no
   effect, idempotent requests are deduplicated by the destination,
   non-idempotent requests may take effect once per delivery. */
one sig Safe, Idempotent, NonIdempotent extends Kind {}

abstract sig Phase {}
/* Durable ledger phases of an open call.
   Pending : committed, never handed to the network.
   Sending : the runtime durably recorded that it may have sent it. */
one sig Pending, Sending extends Phase {}

abstract sig Outcome {}
one sig Done, NotDone, Unknown extends Outcome {}

abstract sig Status {}
one sig Alive, Crashed extends Status {}

one sig Runtime { var status: one Status }

abstract sig Mutant {}
one sig
  SendBeforeCommit,   -- dispatches a request before its decision is committed
  NotDoneAfterSend,   -- reports a timeout after sending as NotDone
  ResendAfterCrash,   -- re-sends a possibly-sent non-idempotent call after recovery
  DoubleCompletion,   -- applies a late response after the call was already completed
  NoWriteAhead,       -- does not record "Sending" durably before sending
  SplitCommit         -- commits business state and obligation ledger in two steps
extends Mutant {}

/* The mutants that are switched on in a given command (static). */
sig Active in Mutant {}

sig Call {
  kind: one Kind,
  var phase: lone Phase,      -- runtime ledger (durable unless NoWriteAhead)
  var outcome: lone Outcome   -- terminal completion applied to the caller's decider
}

var sig Created in Call {}     -- history: calls whose ledger entry was committed
var sig Awaiting in Call {}    -- caller business state: "I am waiting for c"
var sig ToSend in Call {}      -- volatile: the runtime intends to (re)send c
var sig LedgerDue in Call {}   -- volatile (SplitCommit only): ledger write not yet done
var sig InFlight in Call {}    -- request messages in the network
var sig Responses in Call {}   -- response messages addressed to the live runtime
var sig SentOnce, SentTwice in Call {}         -- ground truth: >=1 / >=2 sends
var sig Processed in Call {}                   -- ground truth: destination handled c
var sig AppliedOnce, AppliedTwice in Call {}   -- ground truth: effect applied >=1 / >=2

fun openCalls : set Call { phase.Phase }
pred alive { Runtime.status = Alive }
pred on[m: Mutant] { m in Active }

fact init {
  no Created and no Awaiting and no ToSend and no LedgerDue and no InFlight and no Responses
  no SentOnce and no SentTwice and no Processed and no AppliedOnce and no AppliedTwice
  no phase and no outcome
  Runtime.status = Alive
}

/* ---------- frame helpers ---------- */
pred keepLedger  { phase' = phase and Created' = Created }
pred keepBiz     { Awaiting' = Awaiting }
pred keepOutcome { outcome' = outcome }
pred keepSend    { ToSend' = ToSend and LedgerDue' = LedgerDue }
pred keepNet     { InFlight' = InFlight and Responses' = Responses }
pred keepTruth   { SentOnce' = SentOnce and SentTwice' = SentTwice and Processed' = Processed
                   and AppliedOnce' = AppliedOnce and AppliedTwice' = AppliedTwice }
pred keepStatus  { Runtime.status' = Runtime.status }

pred countSend[c: Call] {
  SentOnce' = SentOnce + c
  SentTwice' = SentTwice + (c & SentOnce)
}

/* ---------- runtime events ---------- */

/* A decision that creates call c is accepted: business state and ledger
   entry are committed in one atomic step. */
pred commit[c: Call] {
  alive and not on[SplitCommit]
  c not in Created + Awaiting and no c.outcome
  Created' = Created + c
  Awaiting' = Awaiting + c
  phase' = phase + c -> Pending
  keepOutcome and keepSend and keepNet and keepTruth and keepStatus
}

/* SplitCommit mutant: the same acceptance done as two separate writes. */
pred commitStateOnly[c: Call] {
  alive and on[SplitCommit]
  c not in Created + Awaiting and no c.outcome
  Awaiting' = Awaiting + c
  LedgerDue' = LedgerDue + c
  ToSend' = ToSend
  keepLedger and keepOutcome and keepNet and keepTruth and keepStatus
}
pred commitLedgerOnly[c: Call] {
  alive and on[SplitCommit]
  c in LedgerDue
  Created' = Created + c
  phase' = phase + c -> Pending
  LedgerDue' = LedgerDue - c
  ToSend' = ToSend
  keepBiz and keepOutcome and keepNet and keepTruth and keepStatus
}

/* Write-ahead: record that the request may be sent, then allow one send. */
pred markSending[c: Call] {
  alive and c.phase = Pending and no c.outcome
  phase' = phase ++ c -> Sending
  ToSend' = ToSend + c
  LedgerDue' = LedgerDue
  Created' = Created
  keepBiz and keepOutcome and keepNet and keepTruth and keepStatus
}

pred send[c: Call] {
  alive and c in ToSend and c.phase = Sending and no c.outcome
  ToSend' = ToSend - c
  InFlight' = InFlight + c
  LedgerDue' = LedgerDue
  countSend[c]
  Responses' = Responses and Processed' = Processed
  AppliedOnce' = AppliedOnce and AppliedTwice' = AppliedTwice
  keepLedger and keepBiz and keepOutcome and keepStatus
}

/* SendBeforeCommit mutant: the request leaves during the decision. */
pred earlySend[c: Call] {
  alive and on[SendBeforeCommit]
  c not in Created and no c.outcome
  InFlight' = InFlight + c
  countSend[c]
  Responses' = Responses and Processed' = Processed
  AppliedOnce' = AppliedOnce and AppliedTwice' = AppliedTwice
  keepLedger and keepBiz and keepOutcome and keepSend and keepStatus
}

/* Bounded retry: only requests that are safe to repeat are re-sent. */
pred retry[c: Call] {
  alive and c.phase = Sending and no c.outcome and c not in ToSend
  c.kind != NonIdempotent
  ToSend' = ToSend + c
  LedgerDue' = LedgerDue
  keepLedger and keepBiz and keepOutcome and keepNet and keepTruth and keepStatus
}

/* The response of the destination reaches the live runtime. */
pred receive[c: Call] {
  alive and c in Responses
  (no c.outcome and some c.phase) or on[DoubleCompletion]
  outcome' = outcome ++ c -> Done
  phase' = phase - c -> Phase
  Awaiting' = Awaiting - c
  ToSend' = ToSend - c
  LedgerDue' = LedgerDue
  Responses' = Responses - c
  Created' = Created and InFlight' = InFlight
  keepTruth and keepStatus
}

/* The call deadline expires. Classification uses only what the runtime
   durably knows: Pending was never sent, Sending may have been sent. */
pred timeout[c: Call] {
  alive and some c.phase and no c.outcome
  let o = ((c.phase = Pending or c.kind = Safe or on[NotDoneAfterSend]) => NotDone else Unknown) |
    outcome' = outcome + c -> o
  phase' = phase - c -> Phase
  Awaiting' = Awaiting - c
  ToSend' = ToSend - c
  LedgerDue' = LedgerDue
  Created' = Created
  keepNet and keepTruth and keepStatus
}

pred crash {
  alive
  Runtime.status' = Crashed
  ToSend' = none            -- volatile intentions are lost
  LedgerDue' = none
  Responses' = none         -- connections to the dead process are gone
  on[NoWriteAhead] => phase' = phase ++ ((phase.Sending) -> Pending) else phase' = phase
  Created' = Created and InFlight' = InFlight
  keepBiz and keepOutcome and keepTruth
}

pred recover {
  not alive
  Runtime.status' = Alive
  let resend = { c: phase.Sending | c.kind != NonIdempotent or on[ResendAfterCrash] },
      unknown = { c: phase.Sending | c.kind = NonIdempotent and not on[ResendAfterCrash] } | {
    ToSend' = ToSend + resend
    LedgerDue' = LedgerDue
    outcome' = outcome + unknown -> Unknown
    phase' = phase - unknown -> Phase
    Awaiting' = Awaiting - unknown
  }
  Created' = Created
  keepNet and keepTruth
}

/* ---------- environment events ---------- */

/* The destination handles a request. Idempotent destinations deduplicate. */
pred deliver[c: Call] {
  c in InFlight
  Processed' = Processed + c
  c.kind = NonIdempotent => {
    AppliedOnce' = AppliedOnce + c
    AppliedTwice' = AppliedTwice + (c & AppliedOnce)
  } else c.kind = Idempotent => {
    AppliedOnce' = AppliedOnce + c
    AppliedTwice' = AppliedTwice
  } else {
    AppliedOnce' = AppliedOnce and AppliedTwice' = AppliedTwice
  }
  /* the network may deliver a duplicate later, or not */
  InFlight' = InFlight - c or InFlight' = InFlight
  alive => Responses' = Responses + c else Responses' = Responses
  SentOnce' = SentOnce and SentTwice' = SentTwice
  keepLedger and keepBiz and keepOutcome and keepSend and keepStatus
}

pred dropRequest[c: Call] {
  c in InFlight
  InFlight' = InFlight - c
  Responses' = Responses
  keepLedger and keepBiz and keepOutcome and keepSend and keepTruth and keepStatus
}

pred dropResponse[c: Call] {
  c in Responses
  Responses' = Responses - c
  InFlight' = InFlight
  keepLedger and keepBiz and keepOutcome and keepSend and keepTruth and keepStatus
}

pred stutter {
  keepLedger and keepBiz and keepOutcome and keepSend and keepNet and keepTruth and keepStatus
}

fact transitions {
  always (
    stutter or crash or recover
    or some c: Call |
      commit[c] or commitStateOnly[c] or commitLedgerOnly[c] or markSending[c] or send[c]
      or earlySend[c] or retry[c] or receive[c] or timeout[c]
      or deliver[c] or dropRequest[c] or dropResponse[c]
  )
}

/* ---------- properties ---------- */

pred P1_NoSendBeforeCommit { always SentOnce in Created }

pred P2_OutcomeStable { always all c: Call, o: Outcome | c.outcome = o implies always c.outcome = o }

pred P3_NoFalseNotDone {
  always all c: Call | (c.outcome = NotDone and c.kind != Safe) implies always c not in AppliedOnce
}

pred P4_AtMostOnceNonIdempotent { always no (SentTwice & kind.NonIdempotent) }

pred P5_DoneIsReal { always all c: Call | c.outcome = Done implies c in Processed }

pred P6_AtomicAcceptance { always Awaiting = openCalls }

pred fairness {
  /* the runtime does not stay down forever */
  always eventually alive
  /* strong fairness of the deadline: a timeout that is enabled infinitely
     often is eventually taken (or the call closes some other way) */
  all c: Call | (always eventually (alive and some c.phase and no c.outcome))
                implies (always eventually (no c.phase))
}

pred L1_EveryObligationCloses {
  fairness implies all c: Call | always (c in Awaiting implies eventually some c.outcome)
}

pred correct { no Active }

/* ---------- checks of the correct runtime (expect no counterexample) ---------- */

check correct_P1 { correct implies P1_NoSendBeforeCommit } for 3 but 1..14 steps expect 0
check correct_P2 { correct implies P2_OutcomeStable } for 3 but 1..14 steps expect 0
check correct_P3 { correct implies P3_NoFalseNotDone } for 3 but 1..14 steps expect 0
check correct_P4 { correct implies P4_AtMostOnceNonIdempotent } for 3 but 1..14 steps expect 0
check correct_P5 { correct implies P5_DoneIsReal } for 3 but 1..14 steps expect 0
check correct_P6 { correct implies P6_AtomicAcceptance } for 3 but 1..14 steps expect 0
check correct_L1 { correct implies L1_EveryObligationCloses } for 2 but 1..12 steps expect 0

/* ---------- each mutant must be caught (expect a counterexample) ---------- */

check mutant_SendBeforeCommit_breaks_P1 { Active = SendBeforeCommit implies P1_NoSendBeforeCommit } for 3 but 1..8 steps expect 1
check mutant_NotDoneAfterSend_breaks_P3 { Active = NotDoneAfterSend implies P3_NoFalseNotDone } for 3 but 1..10 steps expect 1
check mutant_ResendAfterCrash_breaks_P4 { Active = ResendAfterCrash implies P4_AtMostOnceNonIdempotent } for 3 but 1..12 steps expect 1
check mutant_DoubleCompletion_breaks_P2 { Active = DoubleCompletion implies P2_OutcomeStable } for 3 but 1..12 steps expect 1
check mutant_NoWriteAhead_breaks_P4 { Active = NoWriteAhead implies P4_AtMostOnceNonIdempotent } for 3 but 1..12 steps expect 1
check mutant_SplitCommit_breaks_P6 { Active = SplitCommit implies P6_AtomicAcceptance } for 3 but 1..6 steps expect 1
check mutant_SplitCommit_breaks_L1 { Active = SplitCommit implies L1_EveryObligationCloses } for 2 but 1..10 steps expect 1

/* ---------- sanity: the correct model is not vacuous ---------- */

/* A non-idempotent call can complete Done, NotDone, and Unknown. */
run reachable_done    { correct and eventually (some c: kind.NonIdempotent | c.outcome = Done) } for 2 but 1..10 steps expect 1
run reachable_notdone { correct and eventually (some c: kind.NonIdempotent | c.outcome = NotDone) } for 2 but 1..10 steps expect 1
run reachable_unknown { correct and eventually (some c: kind.NonIdempotent | c.outcome = Unknown and c in AppliedOnce) } for 2 but 1..12 steps expect 1
/* Recovery with an open idempotent call leads to a resend. */
run reachable_resend  { correct and eventually (some c: kind.Idempotent | c in SentTwice) } for 2 but 1..14 steps expect 1
/* A crash with an open call is reachable, so recovery paths are exercised. */
run reachable_crash_with_open_call { correct and eventually (not alive and some openCalls) } for 2 but 1..8 steps expect 1
/* The fairness assumption of L1 is satisfiable together with an awaited call
   (otherwise correct_L1 would hold vacuously). */
run fairness_satisfiable { correct and fairness and eventually some Awaiting and eventually crash } for 2 but 1..12 steps expect 1
/* A duplicated request reaches an idempotent destination twice without a second effect. */
run reachable_duplicate_delivery { correct and eventually (some c: kind.Idempotent | c in Processed and c in InFlight and c in AppliedOnce) } for 2 but 1..10 steps expect 1
