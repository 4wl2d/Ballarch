// Knowledge of a composite operation in hardware (rule R14): ballarch_composite
// over the issuer, against the device model (tb_device).
//
// Every scenario runs two operations back to back. The first meets every
// combination of nine fates (tb_pkg) for CONFIG's first two attempts and for
// FIRE's attempt (729 combinations); the second meets one of two fixed fate sets
// (everything answered OK, or CONFIG never accepted), so that a status left
// over from the first operation would be wrong for the second.
//
// For every operation the bench checks:
//   R6   exactly one completion, and none without an operation;
//   R5   no request to the issuer in the cycle whose edge accepts the operation;
//   the status register reads NoEffects before the first operation;
//   R14 soundness: every value of the status register while the operation is
//        current admits the device's ground truth (all: both effects applied;
//        any: at least one), so no definite claim is ever contradicted, and
//        every value is one of the seven the algebra produces (NoEffects only
//        before the first operation);
//   R14 exactness: the final status equals the knowledge computed by
//        supervaluation from the outcomes Core §3.2 requires for the fates,
//        and the completion is that knowledge seen as one call.
// It prints RESULT: PASS or RESULT: FAIL; every seeded bug in the Makefile must fail.
`timescale 1ns / 1ps
module tb_composite;
    import tb_pkg::*;

    parameter int TIMEOUT = 12;
    parameter int MAX_ATTEMPTS = 2;
    parameter int CMD_W = 4;
    parameter int RESP_WINDOW = 4 * (2 ** CMD_W) - 1;
    localparam int ID_W = CMD_W + $clog2(MAX_ATTEMPTS + 1);

    logic clk = 1'b0;
    logic rst_n = 1'b0;
    always #5 clk = ~clk;

    logic op_valid = 1'b0, op_ready;
    logic [7:0] op_data = 8'h00;
    logic op_cmp_valid;
    logic [1:0] op_cmp_kind, k_all, k_any;
    logic m_valid, m_ready;
    logic [1:0] m_op;
    logic [7:0] m_data;
    logic [ID_W-1:0] m_id, s_id;
    logic s_valid, s_err;
    logic [7:0] s_data;
    logic cmd_accept;
    assign cmd_accept = dut.req_valid && dut.req_ready;

    ballarch_composite_top #(.TIMEOUT(TIMEOUT), .MAX_ATTEMPTS(MAX_ATTEMPTS), .CMD_W(CMD_W),
                             .RESP_WINDOW(RESP_WINDOW)) dut (.*);
    tb_device #(.TIMEOUT(TIMEOUT), .CMD_W(CMD_W), .ID_W(ID_W), .RESP_WINDOW(RESP_WINDOW)) dev (.*);

    // ---- upstream: operations offered in order, each held until accepted
    localparam int MAXO = 4;
    int u_head, u_tail;
    always @(negedge clk) begin
        op_valid <= u_head < u_tail;
        op_data <= 8'h5A + 8'(u_head);
    end

    // ---- what the bench saw, per operation
    int cur;                       // the operation the status register describes, or -1
    int o_completions[0:MAXO-1];
    logic [1:0] o_kind[0:MAXO-1];
    logic [1:0] o_final_all[0:MAXO-1], o_final_any[0:MAXO-1];
    bit all_yes[0:MAXO-1], all_no[0:MAXO-1], any_yes[0:MAXO-1], any_no[0:MAXO-1];   // claims made
    bit o_invalid[0:MAXO-1];
    string o_invalid_msg[0:MAXO-1];
    int cmd_op[0:79];              // the operation each issuer command belongs to
    int n_cmd;
    bit before_ok;                 // the status read NoEffects before the first operation
    bit dispatched_early;
    int stray_completions;

    // How often each value of the algebra occurs: [index] while an operation runs, and finally.
    int seen_running[0:7], seen_final[0:7];
    function automatic int value_index(input logic [1:0] a, input logic [1:0] y);
        if (a == K_YES && y == K_YES) return 0;     // Done
        if (a == K_NO && y == K_NO) return 1;       // NotDone
        if (a == K_NO && y == K_YES) return 2;      // Partial
        if (a == K_NO && y == K_MAYBE) return 3;    // NotAllDone
        if (a == K_MAYBE && y == K_YES) return 4;   // SomeDone
        if (a == K_MAYBE && y == K_MAYBE) return 5; // Unknown
        if (a == K_YES && y == K_NO) return 6;      // NoEffects
        return 7;                                   // not a value of the algebra
    endfunction
    function automatic string value_name(input int i);
        case (i)
            0: return "Done";
            1: return "NotDone";
            2: return "Partial";
            3: return "NotAllDone";
            4: return "SomeDone";
            5: return "Unknown";
            6: return "NoEffects";
            default: return "invalid";
        endcase
    endfunction

    function automatic bit valid_pair(input logic [1:0] a, input logic [1:0] y, input bit first);
        if (!(a == K_YES || a == K_NO || a == K_MAYBE)) return 0;
        if (!(y == K_YES || y == K_NO || y == K_MAYBE)) return 0;
        if (a == K_YES && y == K_MAYBE) return 0;     // no operation has (Yes, Maybe)
        if (a == K_MAYBE && y == K_NO) return 0;      // nor (Maybe, No)
        if (a == K_YES && y == K_NO) return first;    // NoEffects: only before any operation
        return 1;
    endfunction

    always @(posedge clk) begin
        if (rst_n) begin
            // The status register as committed at an earlier edge describes the current operation.
            if (cur < 0) begin
                if (!(k_all == K_YES && k_any == K_NO)) before_ok = 0;
            end else begin
                if (!valid_pair(k_all, k_any, 0) && !o_invalid[cur]) begin
                    o_invalid[cur] = 1;
                    o_invalid_msg[cur] = $sformatf("status (%s, %s)", tri_name(k_all), tri_name(k_any));
                end
                if (k_all == K_YES) all_yes[cur] = 1;
                if (k_all == K_NO) all_no[cur] = 1;
                if (k_any == K_YES) any_yes[cur] = 1;
                if (k_any == K_NO) any_no[cur] = 1;
                if (o_completions[cur] == 0 && !op_cmp_valid) seen_running[value_index(k_all, k_any)]++;
            end
            if (op_cmp_valid) begin
                if (cur < 0 || o_completions[cur] != 0) stray_completions++;
                else begin
                    o_completions[cur]++;
                    o_kind[cur] = op_cmp_kind;
                    seen_final[value_index(k_all, k_any)]++;
                    // The status was committed with the completion: it is the final knowledge.
                    o_final_all[cur] = k_all;
                    o_final_any[cur] = k_any;
                end
            end
            if (cmd_accept) begin
                cmd_op[n_cmd] = cur;
                n_cmd++;
            end
            if (op_valid && op_ready) begin
                if (dut.req_valid) dispatched_early = 1'b1;   // R5
                cur++;
                u_head++;
            end
        end
    end

    int failures = 0;
    int scenarios = 0;
    int operations = 0;
    int missed_edges = 0;      // F_FAST or F_DEADLINE answers that could not go at their edge
    initial for (int i = 0; i < 8; i++) begin seen_running[i] = 0; seen_final[i] = 0; end
    string label;

    task automatic fail(input string what);
        failures++;
        if (failures <= 5 || $test$plusargs("verbose")) $display("FAIL %s: %s", label, what);
    endtask

    // The fates of the operations of one scenario: [op][0..1] for CONFIG's attempts, [op][2] for FIRE's.
    int fates[0:MAXO-1][0:2];
    int n_ops;

    localparam int STALL = RESP_WINDOW + 2 * (MAX_ATTEMPTS + 1) * (2 * TIMEOUT + 4) + 20;
    task automatic run_scenario(input string name);
        int idle, sig, last;
        int c;
        logic [1:0] ka, kb, want_all, want_any, want_kind;
        bit b_made, a_applied, b_applied;
        label = name;
        u_head = 0;
        u_tail = 0;
        cur = -1;
        n_cmd = 0;
        before_ok = 1;
        dispatched_early = 0;
        stray_completions = 0;
        for (int o = 0; o < MAXO; o++) begin
            o_completions[o] = 0;
            o_invalid[o] = 0;
            all_yes[o] = 0;
            all_no[o] = 0;
            any_yes[o] = 0;
            any_no[o] = 0;
        end
        @(negedge clk);
        dev.reset_model();
        rst_n = 1'b0;
        // The device meets the issuer's commands in order: CONFIG of each operation,
        // then its FIRE if the composite makes it.
        c = 0;
        for (int o = 0; o < n_ops; o++) begin
            dev.fate[c][0] = fates[o][0];
            for (int a = 1; a < 8; a++) dev.fate[c][a] = fates[o][1];
            c++;
            if (expected_kind(OP_CONFIG, fates[o][0], fates[o][1], MAX_ATTEMPTS) != NOT_DONE) begin
                for (int a = 0; a < 8; a++) dev.fate[c][a] = fates[o][2];
                c++;
            end
        end
        repeat (2) @(posedge clk);
        @(negedge clk) rst_n = 1'b1;
        u_tail = n_ops;
        // Run until both operations completed and no answer is in flight, or until
        // nothing has moved for longer than a correct block can take.
        idle = 0;
        last = -1;
        while ((u_head < u_tail || (cur >= 0 && o_completions[cur] == 0) || dev.queue_size() != 0) && idle < STALL) begin
            @(posedge clk);
            sig = u_head * 65536 + (cur + 1) * 4096 + n_cmd * 64 + dev.queue_size();
            if (sig != last) idle = 0; else idle++;
            last = sig;
        end
        repeat (3) @(posedge clk);

        scenarios++;
        missed_edges += dev.missed_edges;
        if (!before_ok) fail("status before the first operation is not NoEffects");
        if (dispatched_early) fail("request to the issuer in the cycle that accepted the operation (R5)");
        if (stray_completions != 0) fail($sformatf("%0d completions without an operation (R6)", stray_completions));
        for (int o = 0; o < n_ops; o++) begin
            operations++;
            // Ground truth: the effects the device applied for this operation's commands.
            a_applied = 0;
            b_applied = 0;
            for (int k = 0; k < n_cmd; k++) begin
                if (cmd_op[k] == o && dev.config_applies[k] > 0) a_applied = 1;
                if (cmd_op[k] == o && dev.fires[k] > 0) b_applied = 1;
            end
            // The knowledge the algebra derives from the outcomes Core 3.2 requires.
            ka = expected_kind(OP_CONFIG, fates[o][0], fates[o][1], MAX_ATTEMPTS);
            b_made = ka != NOT_DONE;
            kb = expected_kind(OP_FIRE, fates[o][2], fates[o][2], MAX_ATTEMPTS);
            want_all = superval(possible(ka, 1'b1), possible(kb, b_made), 1'b1);
            want_any = superval(possible(ka, 1'b1), possible(kb, b_made), 1'b0);
            want_kind = (want_all == K_YES && want_any == K_YES) ? DONE : want_any == K_NO ? NOT_DONE : UNKNOWN;
            if (o_completions[o] != 1) fail($sformatf("op %0d: %0d completions (R6)", o, o_completions[o]));
            if (o_invalid[o]) fail($sformatf("op %0d: %s, which the algebra never produces", o, o_invalid_msg[o]));
            if (all_yes[o] && !(a_applied && b_applied)) fail($sformatf("op %0d: claimed all=Yes, but not every effect took place (R14)", o));
            if (all_no[o] && (a_applied && b_applied)) fail($sformatf("op %0d: claimed all=No, but every effect took place (R14)", o));
            if (any_yes[o] && !(a_applied || b_applied)) fail($sformatf("op %0d: claimed any=Yes, but no effect took place (R14)", o));
            if (any_no[o] && (a_applied || b_applied)) fail($sformatf("op %0d: claimed any=No, but an effect took place (R14)", o));
            if (o_completions[o] == 1) begin
                if (o_final_all[o] != want_all || o_final_any[o] != want_any)
                    fail($sformatf("op %0d fates=%s,%s,%s: final status (%s, %s), but the algebra derives (%s, %s) from %s and %s",
                                   o, fate_name(fates[o][0]), fate_name(fates[o][1]), fate_name(fates[o][2]),
                                   tri_name(o_final_all[o]), tri_name(o_final_any[o]), tri_name(want_all), tri_name(want_any),
                                   kind_name(ka), kind_name(b_made ? kb : 2'd0)));
                if (o_kind[o] != want_kind)
                    fail($sformatf("op %0d: completion %s, but the knowledge as one call is %s", o, kind_name(o_kind[o]), kind_name(want_kind)));
            end
        end
    endtask

    string then_name;
    initial begin
        for (int f0 = 0; f0 < MAIN_FATES; f0++)
            for (int f1 = 0; f1 < MAIN_FATES; f1++)
                for (int g = 0; g < MAIN_FATES; g++)
                    for (int second = 0; second <= 1; second++) begin
                        n_ops = 2;
                        fates[0][0] = f0;
                        fates[0][1] = f1;
                        fates[0][2] = g;
                        fates[1][0] = second == 0 ? F_OK : F_STALL;
                        fates[1][1] = second == 0 ? F_OK : F_STALL;
                        fates[1][2] = F_OK;
                        then_name = "all OK";
                        if (second != 0) then_name = "CONFIG stalls";
                        run_scenario($sformatf("CONFIG %s,%s FIRE %s, then %s", fate_name(f0), fate_name(f1),
                                               fate_name(g), then_name));
                    end
        for (int i = 0; i < 8; i++)
            if (seen_running[i] + seen_final[i] > 0)
                $display("knowledge %-10s cycles while running %6d, final %4d", value_name(i), seen_running[i], seen_final[i]);
        $display("answers at the first edge or at the deadline that missed it: %0d", missed_edges);
        if (failures == 0) $display("RESULT: PASS (%0d scenarios, %0d operations)", scenarios, operations);
        else $display("RESULT: FAIL (%0d failed checks in %0d scenarios)", failures, scenarios);
        $finish;
    end
endmodule
