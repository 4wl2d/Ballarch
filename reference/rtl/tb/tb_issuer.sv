// Fault exploration of ballarch_issuer against the device model (tb_device).
//
// Scenarios:
//   pairs  every operation (READ, CONFIG, FIRE, the undefined one) under every
//          combination of nine fates of its first two attempts (tb_pkg),
//          followed at once by a second command (READ, CONFIG or FIRE) under
//          each fate of its first attempt, so that late answers to the first
//          command arrive while the second is waiting for its own (attempts
//          after the second meet the second's fate);
//   reset  a command whose answer is still in flight when the issuer alone is
//          reset (the device and the bus keep it), followed by a command that
//          the device refuses and that reuses the first command's tag;
//   wrap   an answer at the end of the response window, then commands answered
//          as fast as the bus allows until the first command's id recurs;
//   dense  one operation repeated, every command answered at the first edge,
//          so that command ids recur as soon as the issuer can reuse them;
//   long   runs in which command ids wrap around several times under random
//          operations and fates, the answer at the window's end included.
// For every command the bench checks, against the device's ground truth and
// the evidence the bus carried:
//   R6  exactly one completion per accepted command, none without one, and
//       every queued command accepted; no offer held for more than TIMEOUT + 1
//       cycles, and after every handshake a retry offer or the completion
//       within TIMEOUT + 2 cycles (the deadlines of the two phases);
//   R5  no bus request in the cycle whose edge accepts the command;
//   R7  the kind is the one Core §3.2 requires for the attempts' fates; NOT_DONE
//       only if no effect was applied; DONE only after an OK answer to the
//       current attempt, only if the effect was applied, and for READ only with
//       that answer's data; UNKNOWN never for READ or the undefined operation;
//   R8  FIRE accepted at most once; at most MAX_ATTEMPTS attempts; a CONFIG
//       applied at most once (the retry was deduplicable);
//   fail closed: the undefined operation is never offered on the bus;
//   tags: no tag is sent again within RESP_WINDOW cycles of its last use,
//       across issuer resets too.
// It prints RESULT: PASS or RESULT: FAIL; every seeded bug in the Makefile must fail.
`timescale 1ns / 1ps
module tb_issuer;
    import tb_pkg::*;

    parameter int TIMEOUT = 12;
    parameter int MAX_ATTEMPTS = 2;
    parameter int CMD_W = 4;
    parameter int RESP_WINDOW = 4 * (2 ** CMD_W) - 1;
    localparam int ID_W = CMD_W + $clog2(MAX_ATTEMPTS + 1);
    localparam int MAXC = 80;

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    always #5 clk = ~clk;

    logic req_valid = 1'b0, req_ready;
    logic [1:0] req_op = OP_READ;
    logic [7:0] req_data = 8'h00;
    logic cmp_valid;
    logic [1:0] cmp_kind;
    logic [7:0] cmp_data;
    logic m_valid, m_ready;
    logic [1:0] m_op;
    logic [7:0] m_data;
    logic [ID_W-1:0] m_id, s_id;
    logic s_valid, s_err;
    logic [7:0] s_data;
    logic cmd_accept;
    assign cmd_accept = req_valid && req_ready;

    ballarch_issuer #(.TIMEOUT(TIMEOUT), .MAX_ATTEMPTS(MAX_ATTEMPTS), .CMD_W(CMD_W),
                      .RESP_WINDOW(RESP_WINDOW)) dut (.*);
    tb_device #(.TIMEOUT(TIMEOUT), .CMD_W(CMD_W), .ID_W(ID_W), .RESP_WINDOW(RESP_WINDOW)) dev (.*);

    // ---- upstream: commands are offered in order, each held until accepted
    logic [1:0] u_op[0:MAXC-1];
    logic [7:0] u_data[0:MAXC-1];
    int u_head, u_tail;

    always @(negedge clk) begin
        req_valid <= u_head < u_tail;
        if (u_head < u_tail) begin
            req_op <= u_op[u_head];
            req_data <= u_data[u_head];
        end
    end

    // ---- what the bench saw, per command
    int n_cmd;                 // commands accepted in this scenario
    int inflight;              // the command awaiting its completion, or -1
    int fates[0:MAXC-1][0:1];
    logic [1:0] c_op[0:MAXC-1];
    int c_completions[0:MAXC-1];
    logic [1:0] c_kind[0:MAXC-1];
    logic [7:0] c_cmp_data[0:MAXC-1];
    bit c_evidence[0:MAXC-1];  // an OK answered its current attempt before the completion
    bit c_lost[0:MAXC-1];      // in flight when the issuer was reset (Transient: no completion owed)
    int stray_completions;
    bit dispatched_early;

    // R6: the deadlines of the two phases of an attempt, observed on the bus.
    int edge_n = 0;
    int offer_len = 0;         // edges at which the current offer was held
    int hs_edge = -1;          // the last handshake, while its phase is open, or -1
    int deadline_misses;
    string deadline_msg;

    always @(posedge clk) begin
        edge_n++;
        if (rst_n) begin
            if (m_valid) begin
                offer_len++;
                if (offer_len == TIMEOUT + 2) begin
                    if (deadline_misses == 0) deadline_msg = $sformatf("offer held for more than %0d cycles at edge %0d", TIMEOUT + 1, edge_n);
                    deadline_misses++;
                end
            end else offer_len = 0;
            if (hs_edge >= 0 && edge_n - hs_edge == TIMEOUT + 3) begin
                if (deadline_misses == 0) deadline_msg = $sformatf("no retry offer or completion within %0d cycles of the handshake at edge %0d", TIMEOUT + 2, hs_edge);
                deadline_misses++;
            end
            if (hs_edge >= 0 && (m_valid || cmp_valid)) hs_edge = -1;
            if (m_valid && m_ready) begin
                offer_len = 0;
                hs_edge = edge_n;
            end
            if (cmp_valid) begin
                if (inflight < 0) stray_completions++;
                else begin
                    c_completions[inflight]++;
                    c_kind[inflight] = cmp_kind;
                    c_cmp_data[inflight] = cmp_data;
                    c_evidence[inflight] = dev.ok_latest[inflight];
                    inflight = -1;
                end
            end
            if (req_valid && req_ready) begin
                if (m_valid) dispatched_early = 1'b1;   // R5
                c_op[n_cmd] = req_op;
                inflight = n_cmd;
                n_cmd++;
                u_head++;
            end
        end
    end

    int failures = 0;
    int scenarios = 0;
    int commands = 0;
    string label;

    task automatic fail(input string what);
        failures++;
        if (failures <= 5 || $test$plusargs("verbose")) $display("FAIL %s: %s", label, what);
    endtask

    task automatic issuer_reset(input int cycles);
        rst_n = 1'b0;
        if (inflight >= 0) c_lost[inflight] = 1'b1;
        inflight = -1;
        offer_len = 0;
        hs_edge = -1;
        repeat (cycles) @(posedge clk);
        @(negedge clk) rst_n = 1'b1;
    endtask

    task automatic begin_scenario(input string name);
        label = name;
        u_head = 0;
        u_tail = 0;
        n_cmd = 0;
        inflight = -1;
        stray_completions = 0;
        dispatched_early = 1'b0;
        deadline_misses = 0;
        for (int c = 0; c < MAXC; c++) begin
            fates[c][0] = F_OK;
            fates[c][1] = F_OK;
            c_completions[c] = 0;
            c_kind[c] = 2'd0;
            c_cmp_data[c] = 8'h00;
            c_evidence[c] = 1'b0;
            c_lost[c] = 1'b0;
        end
        @(negedge clk);
        dev.reset_model();
        issuer_reset(2);
    endtask

    task automatic push(input logic [1:0] op, input int f0, input int f1);
        u_op[u_tail] = op;
        u_data[u_tail] = 8'h50 + 8'(u_tail);
        fates[u_tail][0] = f0;
        fates[u_tail][1] = f1;
        dev.fate[u_tail][0] = f0;
        for (int a = 1; a < 8; a++) dev.fate[u_tail][a] = f1;
        u_tail++;
    endtask

    // Run until every queued command is accepted and completed (or lost) and
    // no answer is in flight, or until nothing has moved for longer than a
    // correct issuer can take (a stuck issuer then fails the R6 checks).
    localparam int STALL = RESP_WINDOW + (MAX_ATTEMPTS + 1) * (2 * TIMEOUT + 4) + 20;
    task automatic settle();
        int idle, sig, last;
        idle = 0;
        last = -1;
        while ((u_head < u_tail || inflight >= 0 || dev.queue_size() != 0) && idle < STALL) begin
            @(posedge clk);
            sig = u_head * 65536 + (inflight + 1) * 64 + dev.queue_size();
            if (sig != last) idle = 0; else idle++;
            last = sig;
        end
        repeat (3) @(posedge clk);
    endtask

    int missed_edges = 0;      // F_FAST or F_DEADLINE answers that could not go at their edge
    task automatic check_scenario();
        bit applied;
        logic [1:0] want;
        scenarios++;
        missed_edges += dev.missed_edges;
        if (stray_completions != 0) fail($sformatf("%0d completions without a command (R6)", stray_completions));
        if (dispatched_early) fail("bus request in the cycle that accepted the command (R5)");
        if (deadline_misses != 0) fail($sformatf("%0d phases past their deadline, first: %s (R6)", deadline_misses, deadline_msg));
        if (dev.tag_reuses != 0) fail($sformatf("tag reused within the response window: %s", dev.tag_reuse_msg));
        if (n_cmd != u_tail) fail($sformatf("%0d of %0d commands accepted", n_cmd, u_tail));
        for (int c = 0; c < n_cmd; c++) begin
            commands++;
            applied = c_op[c] == OP_CONFIG ? dev.config_applies[c] > 0 : c_op[c] == OP_FIRE ? dev.fires[c] > 0 : 1'b0;
            if (dev.undefined_offers[c] != 0) fail($sformatf("cmd %0d: undefined operation offered on the bus", c));
            if (c_op[c] == OP_UNDEFINED && dev.handshakes[c] + dev.withdrawals[c] != 0)
                fail($sformatf("cmd %0d: undefined operation sent", c));
            if (c_op[c] == OP_FIRE && dev.handshakes[c] > 1) fail($sformatf("cmd %0d: FIRE accepted %0d times (R8)", c, dev.handshakes[c]));
            if (dev.handshakes[c] > MAX_ATTEMPTS) fail($sformatf("cmd %0d: %0d attempts accepted", c, dev.handshakes[c]));
            if (dev.config_applies[c] > 1) fail($sformatf("cmd %0d: CONFIG applied %0d times; the device could not deduplicate the retry (R8)", c, dev.config_applies[c]));
            // A second completion is counted as one without a command; a command lost
            // to an issuer reset is owed none (Transient).
            if (c_lost[c]) begin
            end else if (c_completions[c] != 1) begin
                fail($sformatf("cmd %0d: %0d completions (R6)", c, c_completions[c]));
            end else begin
                want = expected_kind(c_op[c], fates[c][0], fates[c][1], MAX_ATTEMPTS);
                if (c_kind[c] != want)
                    fail($sformatf("cmd %0d op=%0d fates=%s,%s: %s, but Core 3.2 requires %s (R7)", c, c_op[c],
                                   fate_name(fates[c][0]), fate_name(fates[c][1]), kind_name(c_kind[c]), kind_name(want)));
                check_outcome(c, applied);
            end
        end
    endtask

    // R7 against the device's ground truth and the evidence on the bus.
    task automatic check_outcome(input int c, input bit applied);
        case (c_kind[c])
            NOT_DONE: if (effectful(c_op[c]) && applied) fail($sformatf("cmd %0d: NOT_DONE, but the device applied the request (R7)", c));
            DONE: begin
                if (!c_evidence[c]) fail($sformatf("cmd %0d: DONE without an OK answer to its current attempt (R7)", c));
                if (effectful(c_op[c]) && !applied) fail($sformatf("cmd %0d: DONE, but the device did not apply the request (R7)", c));
                if (c_op[c] == OP_READ && c_cmp_data[c] != dev.ok_data[c])
                    fail($sformatf("cmd %0d: READ DONE with data %0h, but its answer carried %0h", c, c_cmp_data[c], dev.ok_data[c]));
            end
            UNKNOWN: if (!effectful(c_op[c])) fail($sformatf("cmd %0d: UNKNOWN for operation %0d, which has no effect (R7)", c, c_op[c]));
            default: ;                           // already reported by the kind check
        endcase
    endtask

    string when_name;
    int lcg = 12345;
    function automatic int next_rand(input int n);
        lcg = (lcg * 1103515245 + 12345) & 32'h7fffffff;
        return (lcg >> 8) % n;
    endfunction

    initial begin
        // Pairs: the first command under every fate of its first two attempts, then at once a second one.
        for (int op = 0; op <= 3; op++)
            for (int f0 = 0; f0 < MAIN_FATES; f0++)
                for (int f1 = 0; f1 < MAIN_FATES; f1++)
                    for (int op2 = 0; op2 <= 2; op2++)
                        for (int g0 = 0; g0 < MAIN_FATES; g0++) begin
                            begin_scenario($sformatf("pair op=%0d fates=%s,%s then op=%0d fate=%s", op,
                                                     fate_name(f0), fate_name(f1), op2, fate_name(g0)));
                            push(op[1:0], f0, f1);
                            push(op2[1:0], g0, F_OK);
                            settle();
                            check_scenario();
                        end
        // Reset: the issuer alone is reset with an answer in flight; the next command reuses its tag.
        for (int op = 0; op <= 2; op++)
            for (int when = 0; when <= 1; when++) begin
                int t;
                when_name = "while waiting";
                if (when != 0) when_name = "after completion";
                begin_scenario($sformatf("reset op=%0d %s", op, when_name));
                push(op[1:0], F_LATE, F_LATE);
                if (when == 0) begin
                    t = 0;
                    while (dev.handshakes[0] == 0 && t < 1000) begin @(posedge clk); t++; end
                    repeat (2) @(posedge clk);
                end else begin
                    t = 0;
                    while (c_completions[0] == 0 && t < 1000) begin @(posedge clk); t++; end
                end
                @(negedge clk);
                issuer_reset(2);
                push(op[1:0], F_REFUSE, F_REFUSE);
                settle();
                check_scenario();
            end
        // Wrap: an answer at the end of the window, then commands answered as fast
        // as the bus allows, until the first command's id recurs.
        for (int op = 0; op <= 2; op++) begin
            begin_scenario($sformatf("wrap op=%0d", op));
            push(op[1:0], F_SLOW, F_SLOW);
            for (int k = 1; k < 2 ** CMD_W; k++) push(OP_READ, F_FAST, F_FAST);
            push(op[1:0], F_REFUSE, F_REFUSE);
            settle();
            check_scenario();
        end
        // Dense: command ids recur as soon as the issuer can reuse them.
        for (int op = 0; op <= 2; op++) begin
            begin_scenario($sformatf("dense op=%0d", op));
            for (int k = 0; k <= 2 ** CMD_W; k++) push(op[1:0], F_FAST, F_FAST);
            settle();
            check_scenario();
        end
        // Long runs: command ids wrap several times under mixed operations and fates.
        for (int run = 0; run < 4; run++) begin
            begin_scenario($sformatf("long run %0d", run));
            for (int k = 0; k < 3 * (2 ** CMD_W) + 5 && k < MAXC; k++)
                push(2'(next_rand(4)), next_rand(MAIN_FATES + 1), next_rand(MAIN_FATES + 1));
            settle();
            check_scenario();
        end
        $display("answers at the first edge or at the deadline that missed it: %0d", missed_edges);
        if (failures == 0)
            $display("RESULT: PASS (%0d scenarios, %0d commands; TIMEOUT=%0d MAX_ATTEMPTS=%0d CMD_W=%0d RESP_WINDOW=%0d)",
                     scenarios, commands, TIMEOUT, MAX_ATTEMPTS, CMD_W, RESP_WINDOW);
        else $display("RESULT: FAIL (%0d failed checks in %0d scenarios)", failures, scenarios);
        $finish;
    end
endmodule
