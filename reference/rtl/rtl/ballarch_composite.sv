`timescale 1ns / 1ps

// A composite Ball: one two-step operation, "configure, then fire", made of two
// calls through the issuer (ballarch_issuer), with a status register that says
// what is known about the operation's effects (rule R14, Core §4.2).
//
// Constituents: A = CONFIG(data), then B = FIRE. B is requested only if A may
// have taken effect; after A completes NOT_DONE the operation stops, and B can
// no longer be made.
//
// Status register: knowledge (all, any) of the current or last operation, in
// strong Kleene logic. Each coordinate is two bits, {may be Yes, may be No}:
//   Yes = 2'b10, No = 2'b01, Maybe = 2'b11 (2'b00 never occurs).
// In this encoding Kleene's AND is {a1 & b1, a0 | b0} and OR is {a1 | b1, a0 & b0}:
// the set of possible truth values of the result.
//
// A constituent's effect is Yes if it completed DONE (the device answered OK,
// so the effect took place), No if it completed NOT_DONE or the operation can
// no longer make it, and Maybe if it completed UNKNOWN, is open, or may still
// be made. all = A AND B, any = A OR B. Every value of the status register is
// therefore a claim about the operation's final effect that the device's
// ground truth admits, from the cycle after the operation is accepted on.
// Before the first operation it reads NoEffects (Yes, No), the knowledge of an
// operation with no constituents. The completion's kind is the knowledge seen
// as one call: DONE if (Yes, Yes), NOT_DONE if any is No, otherwise UNKNOWN.
//
// The block keeps the rules of the issuer: registers with one writer (R1), one
// latch-free always_comb decider (R2), every register updated at the same edge
// (R4), and outputs, including the request to the issuer, from registered
// state only (R5).
//
// Seeded bugs for the negative controls (never defined in a build):
//   MUTANT_K_UNKNOWN_AS_NO   counts an UNKNOWN constituent as No
//   MUTANT_K_UNKNOWN_AS_YES  counts an UNKNOWN constituent as Yes
//   MUTANT_K_ANY_AS_AND      computes any with AND
//   MUTANT_K_UNSENT_AS_NO    counts B as No until it is requested, which claims all = No
//                            for an operation that may still complete
//   MUTANT_K_WEAK_KLEENE     uses weak Kleene connectives (Maybe absorbs): honest but not exact
//   MUTANT_K_COMB_REQUEST    requests A combinationally from the upstream operation
//   MUTANT_K_PLAIN_AND       ANDs the two-bit encodings bit by bit, as if they were plain bits
//   MUTANT_K_RESET_UNKNOWN   starts the status register at Unknown instead of NoEffects
//   MUTANT_K_SILENT_STOP     completes nothing when the operation stops after A's NOT_DONE
//   MUTANT_K_REPORT_TWICE    holds the completion for two cycles
module ballarch_composite (
    input  logic       clk,
    input  logic       rst_n,
    // Upstream: one operation and its completion.
    input  logic       op_valid,
    output logic       op_ready,
    input  logic [7:0] op_data,
    output logic       op_cmp_valid,
    output logic [1:0] op_cmp_kind,
    // Status register: knowledge (all, any) of the current or last operation.
    output logic [1:0] k_all,
    output logic [1:0] k_any,
    // The port to the issuer.
    output logic       req_valid,
    input  logic       req_ready,
    output logic [1:0] req_op,
    output logic [7:0] req_data,
    input  logic       cmp_valid,
    input  logic [1:0] cmp_kind
);
    localparam logic [1:0] OP_CONFIG = 2'd1;
    localparam logic [1:0] OP_FIRE = 2'd2;

    localparam logic [1:0] DONE = 2'd1;
    localparam logic [1:0] NOT_DONE = 2'd2;
    localparam logic [1:0] UNKNOWN = 2'd3;

    localparam logic [1:0] K_YES = 2'b10;
    localparam logic [1:0] K_NO = 2'b01;
    localparam logic [1:0] K_MAYBE = 2'b11;

    function automatic logic [1:0] k_and(input logic [1:0] a, input logic [1:0] b);
`ifdef MUTANT_K_PLAIN_AND
        k_and = a & b;
`else
        k_and = (a & b & 2'b10) | ((a | b) & 2'b01);   // {a1 & b1, a0 | b0}
`endif
`ifdef MUTANT_K_WEAK_KLEENE
        if (a == K_MAYBE || b == K_MAYBE) k_and = K_MAYBE;
`endif
    endfunction

    function automatic logic [1:0] k_or(input logic [1:0] a, input logic [1:0] b);
        k_or = ((a | b) & 2'b10) | (a & b & 2'b01);    // {a1 | b1, a0 & b0}
`ifdef MUTANT_K_WEAK_KLEENE
        if (a == K_MAYBE || b == K_MAYBE) k_or = K_MAYBE;
`endif
    endfunction

    // What one call's outcome establishes about its effect.
    function automatic logic [1:0] effect(input logic [1:0] kind);
        case (kind)
            DONE: effect = K_YES;
            NOT_DONE: effect = K_NO;
`ifdef MUTANT_K_UNKNOWN_AS_NO
            UNKNOWN: effect = K_NO;
`elsif MUTANT_K_UNKNOWN_AS_YES
            UNKNOWN: effect = K_YES;
`endif
            default: effect = K_MAYBE;  // UNKNOWN, and any kind the issuer does not define
        endcase
    endfunction

    typedef enum logic [2:0] {IDLE, ISSUE_A, WAIT_A, ISSUE_B, WAIT_B, REPORT} stage_t;

    // ---- committed state (one owner) and its next value
    stage_t stage_q, stage_d;
    logic [7:0] data_q, data_d;
    logic [1:0] a_q, a_d, b_q, b_d;           // constituent effects
    logic [1:0] all_q, all_d, any_q, any_d;   // the status register
    logic [1:0] kind_q, kind_d;

    // ---- the decider
    always_comb begin
        stage_d = stage_q;
        data_d = data_q;
        a_d = a_q;
        b_d = b_q;
        all_d = all_q;
        any_d = any_q;
        kind_d = kind_q;
        case (stage_q)
            IDLE: if (op_valid) begin
                stage_d = ISSUE_A;
                data_d = op_data;
                a_d = K_MAYBE;                  // requested by this decision
`ifdef MUTANT_K_UNSENT_AS_NO
                b_d = K_NO;
`else
                b_d = K_MAYBE;                  // not requested yet, but the operation may still make it
`endif
            end
            ISSUE_A: if (req_ready) stage_d = WAIT_A;
            WAIT_A: if (cmp_valid) begin
                a_d = effect(cmp_kind);
                if (cmp_kind == NOT_DONE) begin
                    b_d = K_NO;                 // the operation stops: B can no longer be made
`ifdef MUTANT_K_SILENT_STOP
                    stage_d = IDLE;
`else
                    stage_d = REPORT;
`endif
                end else begin
`ifdef MUTANT_K_UNSENT_AS_NO
                    b_d = K_MAYBE;
`endif
                    stage_d = ISSUE_B;
                end
            end
            ISSUE_B: if (req_ready) stage_d = WAIT_B;
            WAIT_B: if (cmp_valid) begin
                b_d = effect(cmp_kind);
                stage_d = REPORT;
            end
`ifdef MUTANT_K_REPORT_TWICE
            REPORT: if (data_q != 8'h00) data_d = 8'h00; else stage_d = IDLE;
`else
            REPORT: stage_d = IDLE;
`endif
            default: stage_d = IDLE;
        endcase
        // The status describes the operation from the edge that accepts it.
        if (stage_d != IDLE) begin
            all_d = k_and(a_d, b_d);
`ifdef MUTANT_K_ANY_AS_AND
            any_d = k_and(a_d, b_d);
`else
            any_d = k_or(a_d, b_d);
`endif
        end
        kind_d = (all_d == K_YES && any_d == K_YES) ? DONE : any_d == K_NO ? NOT_DONE : UNKNOWN;
    end

    // ---- the commit
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            stage_q <= IDLE;
            data_q <= 8'd0;
            a_q <= K_NO;
            b_q <= K_NO;
`ifdef MUTANT_K_RESET_UNKNOWN
            all_q <= K_MAYBE;
            any_q <= K_MAYBE;
`else
            all_q <= K_YES;                     // NoEffects: no operation yet
            any_q <= K_NO;
`endif
            kind_q <= NOT_DONE;
        end else begin
            stage_q <= stage_d;
            data_q <= data_d;
            a_q <= a_d;
            b_q <= b_d;
            all_q <= all_d;
            any_q <= any_d;
            kind_q <= kind_d;
        end
    end

    // ---- dispatch: outputs depend on committed state only
    assign op_ready = stage_q == IDLE;
    assign op_cmp_valid = stage_q == REPORT;
    assign op_cmp_kind = kind_q;
    assign k_all = all_q;
    assign k_any = any_q;
`ifdef MUTANT_K_COMB_REQUEST
    assign req_valid = stage_q == ISSUE_A || stage_q == ISSUE_B || (stage_q == IDLE && op_valid);
`else
    assign req_valid = stage_q == ISSUE_A || stage_q == ISSUE_B;
`endif
    assign req_op = stage_q == ISSUE_B ? OP_FIRE : OP_CONFIG;
    assign req_data = data_q;
endmodule
