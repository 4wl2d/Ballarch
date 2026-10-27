#!/usr/bin/env python3
"""Checks Ballarch conformance traces against the trace properties T3 and T5-T9.

A conformance trace is a JSON Lines file in the format of tools/TRACE-FORMAT.md:
the events a runtime emitted under fault injection, plus the ground truth of the
simulated destinations. The properties are the language-neutral form of the
runtime rules R3 and R5-R9 of the Ballarch Core specification:

  T3  decisions of one instance never overlap (R3)
  T5  no call is sent before the decision that created it was committed (R5)
  T6  each call is completed at most once, only after it was committed, and exactly once at quiescence (R6)
  T7  NotDone means nothing was applied; Done means the destination handled it (R7)
  T8  a NonIdempotent call is sent at most once and applied at most once (R8)
  T9  an accepted request is answered at most once (R9)

The Kotlin reference checks the same properties (ballarch-testkit, Oracles.check)
and reports the same messages with R in place of T.

Usage: python3 tools/oracles.py [--quiet] [--expect-violations] TRACE.jsonl...

Prints one line per violation, "TRACE: MESSAGE", and a summary on stderr unless
--quiet is given. Exit status: 0 if every trace satisfies every property, 1 if a
property is violated, 2 if a file cannot be read or is not a valid trace.

With --expect-violations, every trace must violate some property (for seeded
violations): the script prints "TRACE: no violation found" for each trace that
passes, and exits with 0 if none passes, 1 if one does, 2 if a file is invalid.
Python 3.8 or later, standard library only.
"""
from __future__ import annotations

import json
import sys
from collections import Counter

FORMAT = "ballarch-trace"
VERSION = 1
PROFILES = ("Durable", "Transient")
EFFECT_CLASSES = ("Safe", "Idempotent", "NonIdempotent")
NOT_DONE_REASONS = (
    "Refused", "Overloaded", "Cancelled", "DeadlineBeforeSend", "NoAnswer", "NotApplied", "IdempotencyConflict", "Fault",
)
UNKNOWN_REASONS = ("DeadlineAfterSend", "LostAfterSend", "RecoveredAfterCrash", "RetriesExhausted")
# Calls on these ports go to the runtime's own timer or to other Balls, not to a simulated destination.
TIMER_PORT = "ballarch.timer"
BALL_PORT_PREFIX = "ball:"

# Fields of each runtime event besides "event", "at" and "epoch". The kinds are checked by _value().
RUNTIME_EVENTS = {
    "DecideStarted": {"instance": "instance", "input": "str"},
    "DecideFinished": {"instance": "instance"},
    "Committed": {
        "instance": "instance",
        "revision": "int",
        "newCalls": "calls",
        "completedCall": "call?",
        "completedOutcome": "outcome?",
        "replies": "strs",
        "published": "int",
    },
    "CallSent": {"call": "call", "port": "str", "attempt": "int"},
    "CallResolved": {"call": "call", "outcome": "outcome"},
    "LateReportDropped": {"call": "call", "report": "str"},
    "RequestRejected": {"instance": "instance", "request": "str", "outcome": "outcome"},
    "Replied": {"instance": "instance", "request": "str", "outcome": "outcome"},
    "Fault": {"instance": "instance", "message": "str"},
    "ReplyOverdue": {"instance": "instance", "request": "str"},
    "Recovered": {"incarnation": "int", "instances": "int"},
}
DESTINATION = {
    "epoch": "int", "call": "call", "effectClass": "effect", "deliveries": "int", "processed": "bool", "applied": "int",
}


class TraceError(Exception):
    """A file that is not a valid trace. [line] is 1-based, or 0 for the file as a whole."""

    def __init__(self, line: int, message: str):
        super().__init__(message)
        self.line = line


class Event:
    """One runtime event: its name, time, epoch, decoded fields and line number."""

    def __init__(self, name: str, at: int, epoch: int, fields: dict, line: int = 0):
        self.name = name
        self.at = at
        self.epoch = epoch
        self.fields = fields
        self.line = line

    def __getitem__(self, field: str):
        return self.fields[field]


class Destination:
    """Ground truth for one call in one epoch."""

    def __init__(self, effect: str, deliveries: int, processed: bool, applied: int, line: int = 0):
        self.effect = effect
        self.deliveries = deliveries
        self.processed = processed
        self.applied = applied
        self.line = line


class Trace:
    def __init__(self, profile: str, events: list, truth: dict, quiescent: bool):
        self.profile = profile
        self.events = events  # runtime events in order
        self.truth = truth  # epoch -> {call: Destination}
        self.quiescent = quiescent

    def epochs(self) -> int:
        """Number of epochs: 1 for a Durable trace, even if it is empty; under Transient, the last epoch plus 1."""
        if self.profile == "Durable":
            return 1
        return self.events[-1].epoch + 1 if self.events else 0

    def segments(self) -> list:
        """The runtime events of each epoch."""
        out = [[] for _ in range(self.epochs())]
        for e in self.events:
            out[e.epoch].append(e)
        return out


def is_simulated_port(port: str) -> bool:
    """Whether a call on [port] goes to a simulated destination: every port but the timer and other Balls' ports."""
    return port != TIMER_PORT and not port.startswith(BALL_PORT_PREFIX)


# ---------------------------------------------------------------- reading


def _value(kind: str, raw, where: str):
    """Decodes one field. Instances become (type, key); calls become ((type, key), key); outcomes become (kind, reason)."""
    if kind.endswith("?"):
        return None if raw is None else _value(kind[:-1], raw, where)
    if kind == "int":
        if isinstance(raw, bool) or not isinstance(raw, int):
            raise ValueError(f"{where} must be an integer")
        return raw
    if kind == "str":
        if not isinstance(raw, str):
            raise ValueError(f"{where} must be a string")
        return raw
    if kind == "bool":
        if not isinstance(raw, bool):
            raise ValueError(f"{where} must be true or false")
        return raw
    if kind == "effect":
        if raw not in EFFECT_CLASSES:
            raise ValueError(f"{where} must be one of {', '.join(EFFECT_CLASSES)}")
        return raw
    if kind == "instance":
        if not isinstance(raw, dict) or not isinstance(raw.get("type"), str) or not isinstance(raw.get("key"), str):
            raise ValueError(f"{where} must be an object with string fields type and key")
        return (raw["type"], raw["key"])
    if kind == "call":
        if not isinstance(raw, dict) or not isinstance(raw.get("key"), str):
            raise ValueError(f"{where} must be an object with an instance and a string key")
        return (_value("instance", raw.get("instance"), where + ".instance"), raw["key"])
    if kind == "calls":
        if not isinstance(raw, list):
            raise ValueError(f"{where} must be an array of calls")
        return [_value("call", c, f"{where}[{i}]") for i, c in enumerate(raw)]
    if kind == "strs":
        if not isinstance(raw, list):
            raise ValueError(f"{where} must be an array of strings")
        return [_value("str", s, f"{where}[{i}]") for i, s in enumerate(raw)]
    if kind == "outcome":
        if not isinstance(raw, dict):
            raise ValueError(f"{where} must be an outcome object")
        k, reason = raw.get("kind"), raw.get("reason")
        if k == "Done":
            return ("Done", None)
        if k == "NotDone" and reason in NOT_DONE_REASONS:
            return ("NotDone", reason)
        if k == "Unknown" and reason in UNKNOWN_REASONS:
            return ("Unknown", reason)
        raise ValueError(f"{where} must have kind Done, NotDone or Unknown, and a known reason unless Done")
    raise AssertionError(kind)


def _fields(obj: dict, spec: dict, name: str) -> dict:
    out = {}
    for field, kind in spec.items():
        if field not in obj:
            raise ValueError(f"{name} lacks field '{field}'")
        out[field] = _value(kind, obj[field], f"{name}.{field}")
    return out


def parse(lines) -> Trace:
    """Reads a trace from an iterable of text lines. Raises TraceError if it is not a valid trace."""
    profile = None
    quiescent = None
    events = []
    truth = {}
    expected_epoch = 0
    number = 0
    for number, text in enumerate(lines, start=1):
        if not text.strip():
            continue
        try:
            obj = json.loads(text)
        except ValueError as e:
            raise TraceError(number, f"not JSON: {e}") from None
        if not isinstance(obj, dict) or not isinstance(obj.get("event"), str):
            raise TraceError(number, "a line must be a JSON object with a string field 'event'")
        name = obj["event"]
        try:
            if profile is None:
                if name != "Header":
                    raise ValueError("the first line must be the Header")
                if obj.get("format") != FORMAT or obj.get("version") != VERSION:
                    raise ValueError(f"unsupported format {obj.get('format')!r} version {obj.get('version')!r}; "
                                     f"expected {FORMAT!r} version {VERSION}")
                if obj.get("profile") not in PROFILES:
                    raise ValueError(f"Header.profile must be one of {', '.join(PROFILES)}")
                profile = obj["profile"]
            elif quiescent is not None:
                raise ValueError("nothing may follow the End line")
            elif name == "Header":
                raise ValueError("a second Header")
            elif name == "End":
                quiescent = _value("bool", obj.get("quiescent"), "End.quiescent")
            elif name == "Destination":
                f = _fields(obj, DESTINATION, name)
                if f["epoch"] < 0:
                    raise ValueError("Destination.epoch must not be negative")
                calls = truth.setdefault(f["epoch"], {})
                if f["call"] in calls:
                    raise ValueError(f"a second Destination line for {_call(f['call'])} in epoch {f['epoch']}")
                calls[f["call"]] = Destination(f["effectClass"], f["deliveries"], f["processed"], f["applied"], number)
            elif name in RUNTIME_EVENTS:
                at = _value("int", obj.get("at"), name + ".at")
                epoch = _value("int", obj.get("epoch"), name + ".epoch")
                if profile == "Transient" and name == "Recovered" and events:
                    expected_epoch += 1
                if epoch != expected_epoch:
                    raise ValueError(f"{name}.epoch is {epoch}; expected {expected_epoch} "
                                     "(Durable: always 0; Transient: +1 at each Recovered after the first event)")
                events.append(Event(name, at, epoch, _fields(obj, RUNTIME_EVENTS[name], name), number))
            else:
                raise ValueError(f"unknown event '{name}'")
        except ValueError as e:
            raise TraceError(number, str(e)) from None
    if profile is None:
        raise TraceError(0, "empty file: no Header line")
    if quiescent is None:
        raise TraceError(number, "no End line: the trace is incomplete")
    trace = Trace(profile, events, truth, quiescent)
    _check_ground_truth(trace)
    return trace


def _check_ground_truth(trace: Trace):
    """Raises TraceError, at the earliest offending line, unless each epoch has exactly one Destination line for each
    call it sent on a simulated port, and no other Destination line."""
    epochs = trace.epochs()
    sent = {}  # (epoch, call) -> (line, port) of its first CallSent on a simulated port
    for e in trace.events:
        if e.name == "CallSent" and is_simulated_port(e["port"]):
            sent.setdefault((e.epoch, e["call"]), (e.line, e["port"]))
    problems = []
    for epoch, calls in trace.truth.items():
        for c, d in calls.items():
            if epoch >= epochs:
                have = "no runtime events" if epochs == 0 else "only epoch 0" if epochs == 1 else f"epochs 0 to {epochs - 1}"
                problems.append((d.line, f"Destination.epoch is {epoch}, but the trace has {have}"))
            elif (epoch, c) not in sent:
                problems.append((d.line, f"the Destination line of {_call(c)} in epoch {epoch} has no CallSent "
                                         "on a simulated port in that epoch"))
    for (epoch, c), (line, port) in sent.items():
        if c not in trace.truth.get(epoch, {}):
            problems.append((line, f"{_call(c)} was sent on the simulated port '{port}' in epoch {epoch} but has no "
                                   "Destination line; each call handed to a simulated destination needs one, "
                                   "even if every request was lost"))
    if problems:
        line, message = min(problems)
        raise TraceError(line, message)


def load(path: str) -> Trace:
    with open(path, encoding="utf-8") as f:
        return parse(f)


# ---------------------------------------------------------------- properties


def _instance(i) -> str:
    return f"{i[0]}/{i[1]}"


def _call(c) -> str:
    return f"{_instance(c[0])}#{c[1]}"


def check(trace: Trace) -> list:
    """Every violation of T3 and T5-T9, as messages. Under the Transient profile each epoch is judged on its own."""
    v = []
    _serial_processing(trace.events, v)
    segments = trace.segments()
    for epoch, segment in enumerate(segments):
        truth = trace.truth.get(epoch, {})
        before = len(v)
        _commit_before_dispatch(segment, v)
        # Liveness is owed only by an epoch that ran to quiescence without losing its state.
        live = trace.quiescent and (trace.profile == "Durable" or epoch == len(segments) - 1)
        _one_completion_per_call(segment, v, live)
        _honest_outcomes(segment, truth, v)
        _no_resend_of_non_idempotent(segment, truth, v)
        _one_reply_per_request(segment, v)
        if len(segments) > 1:
            v[before:] = [f"[epoch {epoch}] {m}" for m in v[before:]]
    return v


def _serial_processing(events, v):
    """T3: between DecideStarted(i) and the next DecideFinished(i) or Recovered, no DecideStarted(i) occurs."""
    open_ = set()
    for e in events:
        if e.name == "DecideStarted":
            if e["instance"] in open_:
                v.append(f"T3: overlapping decisions on {_instance(e['instance'])} at {e.at}")
            open_.add(e["instance"])
        elif e.name == "DecideFinished":
            open_.discard(e["instance"])
        elif e.name == "Recovered":
            open_.clear()


def _commit_before_dispatch(events, v):
    """T5: every CallSent(c) is preceded, in its epoch, by a Committed whose newCalls contains c."""
    committed = set()
    for e in events:
        if e.name == "Committed":
            committed.update(e["newCalls"])
        elif e.name == "CallSent" and e["call"] not in committed:
            v.append(f"T5: {_call(e['call'])} sent before its decision was committed")


def _one_completion_per_call(events, v, live):
    """T6: a call is the completedCall of at most one Committed, and only of one that follows the Committed whose
    newCalls contains it; of exactly one at quiescence when liveness is owed."""
    created = {}  # ordered set
    completed = Counter()
    for e in events:
        if e.name == "Committed":
            c = e["completedCall"]
            if c is not None:
                if c not in created and c not in completed:
                    v.append(f"T6: {_call(c)} completed but never committed")
                completed[c] += 1
            for n in e["newCalls"]:
                created.setdefault(n, None)
    for c, n in completed.items():
        if n > 1:
            v.append(f"T6: {_call(c)} completed {n} times")
    if live:
        for c in created:
            if c not in completed:
                v.append(f"T6: {_call(c)} never completed")


def _honest_outcomes(events, truth, v):
    """T7: NotDone for a call on a non-Safe destination implies it was applied 0 times; Done implies it was processed.

    Counts are those at the end of the run, so late deliveries count. Calls without a Destination line (Ball ports,
    calls never sent, timers) are not judged."""
    for e in events:
        if e.name != "Committed" or e["completedCall"] is None or e["completedOutcome"] is None:
            continue
        c = e["completedCall"]
        kind, reason = e["completedOutcome"]
        d = truth.get(c)
        if d is None:
            continue
        if kind == "NotDone" and d.effect != "Safe" and d.applied > 0:
            v.append(f"T7: {_call(c)} reported NotDone({reason}) but its effect was applied {d.applied} time(s)")
        elif kind == "Done" and not d.processed:
            v.append(f"T7: {_call(c)} reported Done but the destination never handled it")


def _no_resend_of_non_idempotent(events, truth, v):
    """T8: a call whose destination is NonIdempotent has at most one CallSent and is applied at most once."""
    sends = Counter(e["call"] for e in events if e.name == "CallSent")
    for c, n in sends.items():
        d = truth.get(c)
        if n > 1 and d is not None and d.effect == "NonIdempotent":
            v.append(f"T8: non-idempotent {_call(c)} sent {n} times")
    for c, d in truth.items():
        if d.applied > 1 and d.effect == "NonIdempotent":
            v.append(f"T8: non-idempotent {_call(c)} applied {d.applied} times")


def _one_reply_per_request(events, v):
    """T9: at most one Replied per (instance, request)."""
    replies = Counter((e["instance"], e["request"]) for e in events if e.name == "Replied")
    for (i, r), n in replies.items():
        if n > 1:
            v.append(f"T9: {_instance(i)} replied {n} times to {r}")


# ---------------------------------------------------------------- command line


def main(argv: list) -> int:
    options = ("--quiet", "--expect-violations")
    quiet = "--quiet" in argv
    expect = "--expect-violations" in argv
    paths = [a for a in argv if a not in options]
    if not paths or any(p.startswith("-") for p in paths):
        print(__doc__.strip().split("\n\n")[0], file=sys.stderr)
        print("usage: python3 tools/oracles.py [--quiet] [--expect-violations] TRACE.jsonl...", file=sys.stderr)
        return 2
    violations = 0
    invalid = 0
    passed = 0
    for path in paths:
        try:
            trace = load(path)
        except OSError as e:
            print(f"{path}: cannot read: {e.strerror or e}", file=sys.stderr)
            invalid += 1
            continue
        except TraceError as e:
            print(f"{path}:{e.line}: not a valid trace: {e}", file=sys.stderr)
            invalid += 1
            continue
        messages = check(trace)
        violations += len(messages)
        if not messages:
            passed += 1
            if expect:
                print(f"{path}: no violation found")
        elif not expect:
            for message in messages:
                print(f"{path}: {message}")
    if not quiet:
        summary = f"oracles.py: {len(paths)} trace(s), {violations} violation(s), {invalid} invalid"
        if expect:
            summary += f", {passed} without the expected violation"
        print(summary, file=sys.stderr)
    if invalid:
        return 2
    if expect:
        return 1 if passed else 0
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
