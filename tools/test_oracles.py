#!/usr/bin/env python3
"""Tests of tools/oracles.py: a clean trace passes, each seeded violation is found,
epochs are judged separately, and malformed files, including ground truth that
does not match the calls sent, are rejected.

Run from the repository root: python3 -m unittest discover -s tools -p 'test_*.py'
"""
from __future__ import annotations

import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import oracles  # noqa: E402

ORACLES = Path(__file__).resolve().parent / "oracles.py"
PAYER = {"type": "payer", "key": "p1"}
DONE = {"kind": "Done"}


def call(key: str, instance: dict = PAYER) -> dict:
    return {"instance": instance, "key": key}


def not_done(reason: str) -> dict:
    return {"kind": "NotDone", "reason": reason}


def unknown(reason: str) -> dict:
    return {"kind": "Unknown", "reason": reason}


class TraceBuilder:
    """Builds a trace line by line, numbering epochs as the format requires."""

    def __init__(self, profile: str = "Durable"):
        self.profile = profile
        self.lines = [{"event": "Header", "format": "ballarch-trace", "version": 1, "profile": profile}]
        self.epoch = 0
        self.started = False
        self.at = 1_000

    def event(self, name: str, **fields) -> "TraceBuilder":
        if self.profile == "Transient" and name == "Recovered" and self.started:
            self.epoch += 1
        self.started = True
        self.lines.append({"event": name, "at": self.at, "epoch": self.epoch, **fields})
        self.at += 1
        return self

    def recovered(self, incarnation: int = 1) -> "TraceBuilder":
        return self.event("Recovered", incarnation=incarnation, instances=0)

    def decide(self, instance: dict = PAYER) -> "TraceBuilder":
        self.event("DecideStarted", instance=instance, input="request")
        return self.event("DecideFinished", instance=instance)

    def committed(self, new_calls=(), completed=None, outcome=None, replies=(), instance: dict = PAYER) -> "TraceBuilder":
        return self.event(
            "Committed", instance=instance, revision=1, newCalls=list(new_calls), completedCall=completed,
            completedOutcome=outcome, replies=list(replies), published=0,
        )

    def sent(self, c: dict, attempt: int = 1, port: str = "payment") -> "TraceBuilder":
        return self.event("CallSent", call=c, port=port, attempt=attempt)

    def replied(self, request: str, outcome: dict = DONE, instance: dict = PAYER) -> "TraceBuilder":
        return self.event("Replied", instance=instance, request=request, outcome=outcome)

    def destination(self, c: dict, effect: str = "NonIdempotent", deliveries: int = 1, processed: bool = True,
                    applied: int = 1, epoch: int = 0) -> "TraceBuilder":
        self.lines.append({"event": "Destination", "epoch": epoch, "call": c, "effectClass": effect,
                           "deliveries": deliveries, "processed": processed, "applied": applied})
        return self

    def end(self, quiescent: bool = True) -> "TraceBuilder":
        self.lines.append({"event": "End", "quiescent": quiescent})
        return self

    def text(self) -> str:
        return "".join(json.dumps(line) + "\n" for line in self.lines)

    def violations(self) -> list:
        return oracles.check(oracles.parse(io.StringIO(self.text())))


def paid_run(outcome: dict = DONE, applied: int = 1, processed: bool = True) -> TraceBuilder:
    """A payer that makes one non-idempotent call and replies with its outcome: the clean shape."""
    c = call("1.0")
    t = TraceBuilder().recovered().decide().committed(new_calls=[c]).sent(c)
    t.event("CallResolved", call=c, outcome=outcome)
    t.decide().committed(completed=c, outcome=outcome, replies=["ext:1:0"]).replied("ext:1:0")
    return t.destination(c, applied=applied, processed=processed).end()


class CleanTraces(unittest.TestCase):
    def test_a_clean_run_passes(self):
        self.assertEqual([], paid_run().violations())

    def test_honest_outcomes_of_every_kind_pass(self):
        self.assertEqual([], paid_run(unknown("LostAfterSend"), applied=1).violations())
        self.assertEqual([], paid_run(unknown("LostAfterSend"), applied=0, processed=False).violations())
        self.assertEqual([], paid_run(not_done("NotApplied"), applied=0, processed=False).violations())

    def test_a_safe_call_may_end_not_done_after_it_was_processed(self):
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c).sent(c, attempt=2)
        t.committed(completed=c, outcome=not_done("NoAnswer")).destination(c, "Safe", deliveries=2, applied=0).end()
        self.assertEqual([], t.violations())

    def test_an_idempotent_call_may_be_sent_and_delivered_more_than_once(self):
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c).sent(c, attempt=2)
        t.committed(completed=c, outcome=DONE).destination(c, "Idempotent", deliveries=2, applied=1).end()
        self.assertEqual([], t.violations())

    def test_a_recovery_ends_the_decisions_a_crash_interrupted(self):
        t = TraceBuilder().recovered().event("DecideStarted", instance=PAYER, input="request")
        t.recovered(incarnation=2).decide().end()
        self.assertEqual([], t.violations())

    def test_calls_to_other_balls_and_timers_have_no_ground_truth(self):
        c, d = call("1.0"), call("1.1")
        t = TraceBuilder().recovered().committed(new_calls=[c, d]).sent(c, port="ball:counter").sent(d, port="ballarch.timer")
        t.committed(completed=c, outcome=DONE).committed(completed=d, outcome=DONE).end()
        self.assertEqual([], t.violations())


class SeededViolations(unittest.TestCase):
    def test_send_before_commit(self):
        c = call("1.0")
        t = TraceBuilder().recovered().sent(c).committed(new_calls=[c]).committed(completed=c, outcome=DONE)
        t.destination(c).end()
        self.assertEqual(["T5: payer/p1#1.0 sent before its decision was committed"], t.violations())

    def test_double_completion(self):
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c)
        t.committed(completed=c, outcome=DONE).committed(completed=c, outcome=DONE).destination(c).end()
        self.assertEqual(["T6: payer/p1#1.0 completed 2 times"], t.violations())

    def test_completion_of_a_call_never_committed(self):
        c, d = call("1.0"), call("7.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c).committed(completed=c, outcome=DONE)
        t.committed(completed=d, outcome=DONE).destination(c).end()
        self.assertEqual(["T6: payer/p1#7.0 completed but never committed"], t.violations())
        # Completed before the commit that creates it: also a violation, though the call is committed later.
        early = TraceBuilder().recovered().committed(completed=c, outcome=DONE).committed(new_calls=[c]).end(quiescent=False)
        self.assertEqual(["T6: payer/p1#1.0 completed but never committed"], early.violations())
        # Under the Transient profile a call of an earlier epoch is not a call of this one.
        transient = TraceBuilder("Transient").recovered().committed(new_calls=[c]).recovered(incarnation=2)
        transient.committed(completed=c, outcome=DONE)
        self.assertEqual(["[epoch 1] T6: payer/p1#1.0 completed but never committed"], transient.end().violations())

    def test_false_not_done(self):
        self.assertEqual(
            ["T7: payer/p1#1.0 reported NotDone(NoAnswer) but its effect was applied 1 time(s)"],
            paid_run(not_done("NoAnswer"), applied=1).violations(),
        )

    def test_done_that_the_destination_never_handled(self):
        self.assertEqual(
            ["T7: payer/p1#1.0 reported Done but the destination never handled it"],
            paid_run(DONE, applied=0, processed=False).violations(),
        )

    def test_done_for_a_call_whose_every_request_was_lost(self):
        # The call was handed to its destination, so it has a Destination line although nothing arrived.
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c).committed(completed=c, outcome=DONE)
        t.destination(c, deliveries=0, processed=False, applied=0).end()
        self.assertEqual(["T7: payer/p1#1.0 reported Done but the destination never handled it"], t.violations())
        # A writer that left the line out would hide the violation, so such a trace is not valid.
        del t.lines[-2]
        with self.assertRaises(oracles.TraceError) as raised:
            t.violations()
        self.assertIn("has no Destination line", str(raised.exception))
        self.assertEqual(4, raised.exception.line)

    def test_non_idempotent_resend(self):
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c).recovered(incarnation=2).sent(c, attempt=2)
        t.committed(completed=c, outcome=DONE).destination(c, deliveries=2, applied=2).end()
        self.assertEqual(
            ["T8: non-idempotent payer/p1#1.0 sent 2 times", "T8: non-idempotent payer/p1#1.0 applied 2 times"],
            t.violations(),
        )

    def test_double_reply(self):
        t = paid_run()
        t.lines.insert(len(t.lines) - 2, dict(t.lines[-3]))  # repeat the Replied line before the ground truth
        self.assertEqual(["T9: payer/p1 replied 2 times to ext:1:0"], t.violations())

    def test_nested_decide(self):
        t = TraceBuilder().recovered()
        t.event("DecideStarted", instance=PAYER, input="request")
        t.event("DecideStarted", instance=PAYER, input="completion 1.0")
        t.event("DecideFinished", instance=PAYER).end()
        self.assertEqual(["T3: overlapping decisions on payer/p1 at 1002"], t.violations())

    def test_a_call_never_completed_by_a_quiescent_durable_run(self):
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).sent(c).destination(c)
        self.assertEqual(["T6: payer/p1#1.0 never completed"], t.end(quiescent=True).violations())
        t.lines[-1]["quiescent"] = False
        self.assertEqual([], t.violations())

    def test_every_seeded_violation_is_reported_by_the_command_line_with_status_1(self):
        seeded = {
            "send-before-commit": TraceBuilder().recovered().sent(call("1.0")).destination(call("1.0")).end(),
            "false-not-done": paid_run(not_done("NoAnswer"), applied=1),
        }
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for name, trace in seeded.items():
                path = os.path.join(directory, name + ".jsonl")
                Path(path).write_text(trace.text(), encoding="utf-8")
                paths.append(path)
            result = subprocess.run([sys.executable, str(ORACLES), *paths], capture_output=True, text=True)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(
            [f"{paths[0]}: T5: payer/p1#1.0 sent before its decision was committed",
             f"{paths[1]}: T7: payer/p1#1.0 reported NotDone(NoAnswer) but its effect was applied 1 time(s)"],
            result.stdout.splitlines(),
        )
        self.assertIn("2 trace(s), 2 violation(s), 0 invalid", result.stderr)

    def test_expect_violations_fails_on_a_trace_that_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            bad, good = os.path.join(directory, "bad.jsonl"), os.path.join(directory, "good.jsonl")
            Path(bad).write_text(paid_run(not_done("NoAnswer"), applied=1).text(), encoding="utf-8")
            Path(good).write_text(paid_run().text(), encoding="utf-8")
            only_bad = subprocess.run([sys.executable, str(ORACLES), "--expect-violations", bad], capture_output=True, text=True)
            both = subprocess.run([sys.executable, str(ORACLES), "--expect-violations", bad, good], capture_output=True, text=True)
        self.assertEqual((0, ""), (only_bad.returncode, only_bad.stdout), only_bad.stderr)
        self.assertEqual(1, both.returncode, both.stderr)
        self.assertEqual([f"{good}: no violation found"], both.stdout.splitlines())
        self.assertIn("2 trace(s), 1 violation(s), 0 invalid, 1 without the expected violation", both.stderr)


class Epochs(unittest.TestCase):
    def transient(self) -> TraceBuilder:
        """Epoch 0 commits and sends a call, then crashes; epoch 1 reuses the same call identity."""
        c = call("1.0")
        t = TraceBuilder("Transient").recovered().committed(new_calls=[c]).sent(c)
        t.recovered(incarnation=2).committed(new_calls=[c]).sent(c).committed(completed=c, outcome=DONE)
        return t.destination(c, epoch=0).destination(c, epoch=1)

    def test_a_violation_in_a_later_epoch_is_judged_against_that_epochs_ground_truth(self):
        # Epoch 0 applied payer/p1#1.0; epoch 1 reuses the identity, and its own call was never applied.
        c = call("1.0")
        t = TraceBuilder("Transient").recovered().committed(new_calls=[c]).sent(c)
        t.recovered(incarnation=2).committed(new_calls=[c]).sent(c).committed(completed=c, outcome=not_done("NotApplied"))
        t.destination(c, epoch=0).destination(c, deliveries=0, processed=False, applied=0, epoch=1)
        self.assertEqual([], t.end().violations())
        t.lines[-2]["applied"] = 1
        self.assertEqual(["[epoch 1] T7: payer/p1#1.0 reported NotDone(NotApplied) but its effect was applied 1 time(s)"],
                         t.violations())

    def test_a_transient_restart_starts_a_new_world(self):
        # Each epoch has its own call identities and ground truth; the lost call of epoch 0 owes no completion.
        self.assertEqual([], self.transient().end().violations())

    def test_violations_name_their_epoch(self):
        t = self.transient()
        t.lines.insert(-2, {"event": "Replied", "at": 2_000, "epoch": 1, "instance": PAYER, "request": "r", "outcome": DONE})
        t.lines.insert(-2, {"event": "Replied", "at": 2_001, "epoch": 1, "instance": PAYER, "request": "r", "outcome": DONE})
        self.assertEqual(["[epoch 1] T9: payer/p1 replied 2 times to r"], t.end().violations())

    def test_only_the_last_transient_epoch_owes_liveness(self):
        c, d = call("1.0"), call("2.0")
        t = TraceBuilder("Transient").recovered().committed(new_calls=[c]).recovered(incarnation=2).committed(new_calls=[d])
        self.assertEqual(["[epoch 1] T6: payer/p1#2.0 never completed"], t.end().violations())

    def test_a_durable_trace_is_one_epoch_across_restarts(self):
        c = call("1.0")
        t = TraceBuilder().recovered().committed(new_calls=[c]).recovered(incarnation=2).committed(completed=c, outcome=DONE)
        self.assertEqual([], t.end().violations())
        twice = TraceBuilder().recovered().committed(new_calls=[c]).committed(completed=c, outcome=DONE)
        twice.recovered(incarnation=2).committed(completed=c, outcome=DONE)
        self.assertEqual(["T6: payer/p1#1.0 completed 2 times"], twice.end().violations())


class MalformedTraces(unittest.TestCase):
    def assert_invalid(self, text: str, fragment: str):
        with self.assertRaises(oracles.TraceError) as raised:
            oracles.parse(io.StringIO(text))
        self.assertIn(fragment, str(raised.exception))

    def test_malformed_files_are_rejected(self):
        clean = paid_run()
        lines = clean.text().splitlines(keepends=True)
        self.assert_invalid("", "no Header")
        self.assert_invalid("".join(lines[1:]), "first line must be the Header")
        self.assert_invalid("".join(lines[:-1]), "no End line")
        self.assert_invalid(clean.text() + lines[1], "nothing may follow the End line")
        self.assert_invalid(lines[0].replace('"version": 1', '"version": 2') + "".join(lines[1:]), "unsupported format")
        self.assert_invalid(lines[0] + '{"event": "Teleported", "at": 1, "epoch": 0}\n' + "".join(lines[1:]), "unknown event")
        self.assert_invalid(lines[0] + lines[1].replace('"epoch": 0', '"epoch": 1') + "".join(lines[2:]), "epoch is 1; expected 0")
        self.assert_invalid(lines[0] + lines[1].replace('"at": 1000', '"at": "soon"') + "".join(lines[2:]), "at must be an integer")
        self.assert_invalid(clean.text().replace('"kind": "Done"', '"kind": "Maybe"', 1), "kind Done, NotDone or Unknown")
        self.assert_invalid("".join(lines[:-1]) + lines[-2] + lines[-1], "a second Destination line")

    def test_ground_truth_must_match_the_calls_sent_in_its_epoch(self):
        c = call("1.0")
        sent = TraceBuilder().recovered().committed(new_calls=[c]).sent(c)
        # A Durable trace is one epoch.
        sent.destination(c).destination(c, epoch=1).end()
        self.assert_invalid(sent.text(), "Destination.epoch is 1, but the trace has only epoch 0")
        # A Transient trace has as many epochs as its runtime events show.
        transient = TraceBuilder("Transient").recovered().committed(new_calls=[c]).sent(c).recovered(incarnation=2)
        transient.destination(c).destination(c, epoch=2).end()
        self.assert_invalid(transient.text(), "Destination.epoch is 2, but the trace has epochs 0 to 1")
        # Ground truth for a call that the epoch never sent on a simulated port.
        unsent = TraceBuilder().recovered().committed(new_calls=[c]).destination(c).end()
        self.assert_invalid(unsent.text(), "has no CallSent on a simulated port in that epoch")
        to_ball = TraceBuilder().recovered().committed(new_calls=[c]).sent(c, port="ball:counter").destination(c).end()
        self.assert_invalid(to_ball.text(), "has no CallSent on a simulated port in that epoch")
        # Ground truth filed under the wrong epoch: the earliest offending line is reported.
        moved = TraceBuilder("Transient").recovered().committed(new_calls=[c]).sent(c).recovered(incarnation=2)
        moved.destination(c, epoch=1).end()
        self.assert_invalid(moved.text(), "in epoch 0 but has no Destination line")

    def test_the_command_line_reports_invalid_files_with_status_2(self):
        with tempfile.TemporaryDirectory() as directory:
            bad = os.path.join(directory, "bad.jsonl")
            Path(bad).write_text(paid_run().text().splitlines(keepends=True)[0], encoding="utf-8")
            result = subprocess.run([sys.executable, str(ORACLES), bad], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("not a valid trace: no End line", result.stderr)

    def test_main_returns_0_for_clean_traces(self):
        with tempfile.TemporaryDirectory() as directory:
            good = os.path.join(directory, "good.jsonl")
            Path(good).write_text(paid_run().text(), encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                status = oracles.main([good])
        self.assertEqual(0, status)
        self.assertEqual("", out.getvalue())
        self.assertIn("1 trace(s), 0 violation(s), 0 invalid", err.getvalue())


if __name__ == "__main__":
    unittest.main()
