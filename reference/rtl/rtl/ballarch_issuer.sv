`timescale 1ns / 1ps

// Ballarch Core 2 in register-transfer logic: a command issuer that owns one
// obligation at a time.
//
// The block is a Ball. Its state lives in registers written by exactly one
// always_ff (R1, single owner). Its decider is one always_comb block with a
// default for every next-state signal, so it holds no hidden state (R2: a
// latch would be state no clock edge commits). The clock edge commits the
// whole next state at once (R4), and every output is a function of
// registered state only, so nothing is dispatched before the decision that
// requests it is committed (R5).
//
// Downstream it issues transactions on a bus with a request channel
// (m_valid/m_ready) and tagged responses (s_valid/s_id). On this bus the
// master may withdraw a request that was not yet accepted, so the send
// horizon is the m_valid && m_ready handshake. The device applies a request
// when it accepts it, so this bus delivers requests in issue order. (On AXI,
// VALID may not be withdrawn, so the send horizon is the assertion of VALID,
// and only transactions with the same ID stay in order; see README.md.)
//
// Operations and their effect classes, decoded positively (fail closed):
//   READ   (2'd0) Safe
//   CONFIG (2'd1) Idempotent: every attempt of one command carries the same
//                 command id, and the device applies a CONFIG only if it has
//                 not applied one with that id within RESP_WINDOW cycles
//   FIRE   (2'd2) NonIdempotent: sent at most once (R8)
//   2'd3          undefined: completes NOT_DONE without being sent
//
// Every accepted command gets exactly one completion (R6), whose kind is
// chosen from the phase and the operation's effect class (R7):
//   first attempt withdrawn before acceptance   -> NOT_DONE (provably not applied)
//   retry withdrawn, CONFIG                     -> UNKNOWN (an earlier attempt may have applied)
//   response OK                                 -> DONE
//   response ERR on the only attempt            -> NOT_DONE (the device refused)
//   no response by the deadline, READ (Safe)    -> retry, then NOT_DONE
//   no response, CONFIG (Idempotent)            -> retry with a fresh tag, same command id, then UNKNOWN
//   no response, FIRE (NonIdempotent)           -> UNKNOWN, never re-issued (R8)
//
// Tags. The tag of an attempt is {command id, attempt number}: fresh for
// every attempt, with a command part that stays the same across one
// command's attempts. A response is taken only if it carries the current
// attempt's tag. The bus contract is that a response arrives within
// RESP_WINDOW cycles of its handshake, or never. A command starts at least 3
// cycles after the previous command's last handshake, and commands start at
// least 4 cycles apart, so a command id is sent again no sooner than
// 4 * 2**CMD_W cycles after its last use. An attempt's offer lasts at most
// TIMEOUT + 1 cycles and its wait for the answer as long, so the last retry's
// handshake comes at most (MAX_ATTEMPTS - 1) * (2 * TIMEOUT + 2) cycles after
// the first. Two conditions are checked at elaboration, in 64-bit arithmetic:
// RESP_WINDOW < 4 * 2**CMD_W, so that no late response can match a later
// command's attempt and the device has forgotten a command id before it
// recurs; and (MAX_ATTEMPTS - 1) * (2 * TIMEOUT + 2) <= RESP_WINDOW, so that
// the device still remembers a command id when the command's last retry
// arrives. Both bounds are tight: with the check removed, the bench finds a
// wrong outcome at the nearest window each one refuses (see README.md).
// Registers do not survive reset (Transient profile, R13); after a reset the
// block accepts no command for RESP_WINDOW cycles, so every response to an
// attempt made before the reset has arrived, and the device has forgotten its
// command id, before any tag is reused.
//
// Seeded bugs for the negative controls (never defined in a build):
//   MUTANT_RETRY_FIRE                re-issues FIRE after a timeout
//   MUTANT_TIMEOUT_NOT_DONE          reports NOT_DONE when an accepted request times out
//   MUTANT_TIMEOUT_DONE              reports DONE when an accepted CONFIG or FIRE times out
//   MUTANT_WITHDRAWN_RETRY_NOT_DONE  reports NOT_DONE when a retry is withdrawn after an
//                                    earlier attempt was accepted (the first draft's bug)
//   MUTANT_COMB_DISPATCH             drives m_valid combinationally from the upstream request
//   MUTANT_NO_TAG_CHECK              takes any response, whatever its tag
//   MUTANT_SAME_TAG_RETRY            re-sends a retry with the previous attempt's tag
//   MUTANT_RETRY_NEW_CMD             gives a retry a new command id, so the device cannot deduplicate it
//   MUTANT_SHORT_TAG                 counts command ids in two bits, so tags recur within RESP_WINDOW
//   MUTANT_NO_DRAIN                  accepts commands immediately after reset
//   MUTANT_FAIL_OPEN                 sends the undefined operation and retries it like CONFIG
//   MUTANT_READ_DATA                 completes a READ without the data of its response
//   MUTANT_LOST_COMPLETION           drops the completion of a first attempt withdrawn before acceptance
//   MUTANT_DOUBLE_COMPLETION         holds a completion for two cycles
//   MUTANT_STUCK_DRAIN               never ends the wait after reset
//   MUTANT_SHORT_DEADLINE            waits 1 cycle for an answer instead of TIMEOUT
//   MUTANT_SHORT_OFFER               withdraws an offer one cycle before its deadline
//   MUTANT_LONG_OFFER                holds an offer one cycle past its deadline
//   MUTANT_UNCHECKED_PARAMS          drops the parameter check (the Makefile then runs the bench
//                                    at windows the check refuses)
module ballarch_issuer #(
    parameter int TIMEOUT = 12,       // cycles a phase may last before its deadline; at least 1
    parameter int MAX_ATTEMPTS = 2,   // bus attempts for READ and CONFIG; at least 1
    parameter int CMD_W = 4,          // width of the command id, 1 to 28 (4 * 2**CMD_W fits an int)
    // Bus contract: a response arrives within RESP_WINDOW cycles of its handshake
    // or never, and the device keeps a CONFIG's command id for RESP_WINDOW cycles.
    // The default is the longest window the command-id width allows.
    parameter int RESP_WINDOW = 4 * (2 ** CMD_W) - 1
) (
    input  logic       clk,
    input  logic       rst_n,
    // Upstream: the request (inbox) and its completion.
    input  logic       req_valid,
    output logic       req_ready,
    input  logic [1:0] req_op,
    input  logic [7:0] req_data,
    output logic       cmp_valid,
    output logic [1:0] cmp_kind,
    output logic [7:0] cmp_data,
    // Downstream bus. The tag is {command id, attempt number}.
    output logic       m_valid,
    input  logic       m_ready,
    output logic [1:0] m_op,
    output logic [7:0] m_data,
    output logic [CMD_W + $clog2(MAX_ATTEMPTS + 1) - 1:0] m_id,
    input  logic       s_valid,
    input  logic [CMD_W + $clog2(MAX_ATTEMPTS + 1) - 1:0] s_id,
    input  logic       s_err,
    input  logic [7:0] s_data
);
    localparam logic [1:0] OP_READ = 2'd0;    // Safe
    localparam logic [1:0] OP_CONFIG = 2'd1;  // Idempotent (deduplicated by command id)
    localparam logic [1:0] OP_FIRE = 2'd2;    // NonIdempotent

    localparam logic [1:0] DONE = 2'd1;
    localparam logic [1:0] NOT_DONE = 2'd2;
    localparam logic [1:0] UNKNOWN = 2'd3;

    // Counters are sized from the parameters, so no override is truncated (at
    // least one bit each, so that out-of-range values reach the check below).
    localparam int AW = MAX_ATTEMPTS < 1 ? 1 : $clog2(MAX_ATTEMPTS + 1);   // attempt number, 1..MAX_ATTEMPTS
    localparam int CW = CMD_W < 1 ? 1 : CMD_W;                              // command id
    localparam int ID_W = CW + AW;
    localparam int TW = TIMEOUT < 1 ? 1 : $clog2(64'(TIMEOUT) + 64'd1);
    localparam int DW = RESP_WINDOW < 1 ? 1 : $clog2(RESP_WINDOW + 1);
    // A retry must reach the device while it still remembers the command id,
    // and a command id must not recur while a response or the device's memory
    // of it may still be live. 64-bit arithmetic: no parameter value wraps.
    localparam logic [63:0] RETRY_SPAN = (64'(MAX_ATTEMPTS) - 64'd1) * (64'd2 * 64'(TIMEOUT) + 64'd2);
`ifdef MUTANT_UNCHECKED_PARAMS
    localparam bit PARAMS_OK = 1'b1;
`else
    localparam bit PARAMS_OK = TIMEOUT >= 1 && MAX_ATTEMPTS >= 1 && CMD_W >= 1 && CMD_W <= 28
        && RESP_WINDOW >= 0 && RETRY_SPAN <= 64'(RESP_WINDOW) && 64'(RESP_WINDOW) < (64'd4 << CMD_W);
`endif
    if (!PARAMS_OK) begin : g_bad_parameters
        $error("ballarch_issuer: need TIMEOUT >= 1, MAX_ATTEMPTS >= 1, 1 <= CMD_W <= 28 and (MAX_ATTEMPTS-1)*(2*TIMEOUT+2) <= RESP_WINDOW < 4*2**CMD_W");
    end
    // The deadlines of the two phases of an attempt, the offer (PENDING) and the
    // wait for the answer (SENT): each phase's timer counts down from its
    // deadline, so the phase ends at most deadline + 1 edges after it began.
`ifdef MUTANT_SHORT_OFFER
    localparam int OFFER_DEADLINE = TIMEOUT - 1;
`elsif MUTANT_LONG_OFFER
    localparam int OFFER_DEADLINE = TIMEOUT + 1;
`else
    localparam int OFFER_DEADLINE = TIMEOUT;
`endif
`ifdef MUTANT_SHORT_DEADLINE
    localparam int ANSWER_DEADLINE = 1;
`else
    localparam int ANSWER_DEADLINE = TIMEOUT;
`endif
`ifndef SYNTHESIS
    initial if (!PARAMS_OK) $fatal(1, "ballarch_issuer: parameters out of range");
`endif

    typedef enum logic [1:0] {IDLE, PENDING, SENT, REPORT} stage_t;

    // ---- committed state (one owner) and its next value
    stage_t stage_q, stage_d;
    logic [1:0] op_q, op_d, kind_q, kind_d;
    logic [7:0] data_q, data_d, rdata_q, rdata_d;
    logic [CW-1:0] cmd_q, cmd_d;
    logic [AW-1:0] attempts_q, attempts_d;
    logic [TW-1:0] timer_q, timer_d;
    logic [DW-1:0] drain_q, drain_d;
    logic [ID_W-1:0] tag;          // the current attempt's tag, from committed state
    logic defined, retryable;

`ifdef MUTANT_SAME_TAG_RETRY
    assign tag = {cmd_q, AW'(1)};
`else
    assign tag = {cmd_q, attempts_q};
`endif
`ifdef MUTANT_FAIL_OPEN
    assign defined = 1'b1;
    assign retryable = op_q != OP_FIRE;
`else
    assign defined = req_op == OP_READ || req_op == OP_CONFIG || req_op == OP_FIRE;
    assign retryable = op_q == OP_READ || op_q == OP_CONFIG;   // Safe or Idempotent
`endif

    // ---- the decider: next state from committed state and inputs, nothing else
    always_comb begin
        stage_d = stage_q;
        op_d = op_q;
        kind_d = kind_q;
        data_d = data_q;
        rdata_d = rdata_q;
        cmd_d = cmd_q;
        attempts_d = attempts_q;
        timer_d = timer_q;
`ifdef MUTANT_STUCK_DRAIN
        drain_d = drain_q;
`else
        drain_d = drain_q == '0 ? '0 : drain_q - DW'(1);
`endif
        case (stage_q)
`ifdef MUTANT_NO_DRAIN
            IDLE: if (req_valid) begin
`else
            IDLE: if (req_valid && drain_q == '0) begin
`endif
                if (defined) begin
                    stage_d = PENDING;
                    op_d = req_op;
                    data_d = req_data;
`ifdef MUTANT_SHORT_TAG
                    cmd_d = (cmd_q + CW'(1)) & CW'(3);
`else
                    cmd_d = cmd_q + CW'(1);
`endif
                    attempts_d = AW'(1);
                    timer_d = TW'(OFFER_DEADLINE);
                end else begin
                    stage_d = REPORT;               // undefined operation: refused, nothing sent
                    kind_d = NOT_DONE;
                end
            end
            PENDING: if (m_ready) begin             // handshake: the send horizon is crossed
                stage_d = SENT;
                timer_d = TW'(ANSWER_DEADLINE);
            end else if (timer_q == '0) begin       // withdrawn before acceptance
`ifdef MUTANT_LOST_COMPLETION
                if (attempts_q == AW'(1)) stage_d = IDLE; else stage_d = REPORT;
`else
                stage_d = REPORT;
`endif
                // Provably not applied only if no earlier attempt was accepted, or if READ has no effect.
`ifdef MUTANT_WITHDRAWN_RETRY_NOT_DONE
                kind_d = NOT_DONE;
`else
                kind_d = (attempts_q == AW'(1) || op_q == OP_READ) ? NOT_DONE : UNKNOWN;
`endif
            end else begin
                timer_d = timer_q - TW'(1);
            end
`ifdef MUTANT_NO_TAG_CHECK
            SENT: if (s_valid) begin
`else
            SENT: if (s_valid && s_id == tag) begin
`endif
                stage_d = REPORT;
`ifdef MUTANT_READ_DATA
                rdata_d = 8'd0;
`else
                rdata_d = s_data;
`endif
                // A refusal is evidence only for its own attempt.
                kind_d = !s_err ? DONE : (attempts_q == AW'(1) || op_q == OP_READ) ? NOT_DONE : UNKNOWN;
            end else if (timer_q == '0) begin
`ifdef MUTANT_RETRY_FIRE
                if (attempts_q < AW'(MAX_ATTEMPTS)) begin
`else
                if (retryable && attempts_q < AW'(MAX_ATTEMPTS)) begin
`endif
                    stage_d = PENDING;              // runtime-owned retry: fresh tag, same command id
                    attempts_d = attempts_q + AW'(1);
`ifdef MUTANT_RETRY_NEW_CMD
                    cmd_d = cmd_q + CW'(1);
`endif
                    timer_d = TW'(OFFER_DEADLINE);
                end else begin
                    stage_d = REPORT;
`ifdef MUTANT_TIMEOUT_NOT_DONE
                    kind_d = NOT_DONE;
`elsif MUTANT_TIMEOUT_DONE
                    kind_d = op_q == OP_READ ? NOT_DONE : DONE;
`else
                    kind_d = op_q == OP_READ ? NOT_DONE : UNKNOWN;
`endif
                end
            end else begin
                timer_d = timer_q - TW'(1);
            end
`ifdef MUTANT_DOUBLE_COMPLETION
            REPORT: if (attempts_q != '0) attempts_d = '0; else stage_d = IDLE;
`else
            REPORT: stage_d = IDLE;
`endif
            default: stage_d = IDLE;
        endcase
    end

    // ---- the commit: every register at the same edge
    always_ff @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            stage_q <= IDLE;
            op_q <= OP_READ;
            kind_q <= DONE;
            data_q <= 8'd0;
            rdata_q <= 8'd0;
            cmd_q <= '0;
            attempts_q <= '0;
            timer_q <= '0;
            drain_q <= DW'(RESP_WINDOW);
        end else begin
            stage_q <= stage_d;
            op_q <= op_d;
            kind_q <= kind_d;
            data_q <= data_d;
            rdata_q <= rdata_d;
            cmd_q <= cmd_d;
            attempts_q <= attempts_d;
            timer_q <= timer_d;
            drain_q <= drain_d;
        end
    end

    // ---- dispatch: outputs depend on committed state only
`ifdef MUTANT_NO_DRAIN
    assign req_ready = stage_q == IDLE;
`else
    assign req_ready = stage_q == IDLE && drain_q == '0;
`endif
`ifdef MUTANT_COMB_DISPATCH
    assign m_valid = stage_q == PENDING || (stage_q == IDLE && req_valid);
`else
    assign m_valid = stage_q == PENDING;
`endif
    assign m_op = op_q;
    assign m_data = data_q;
    assign m_id = tag;
    assign cmp_valid = stage_q == REPORT;
    assign cmp_kind = kind_q;
    assign cmp_data = rdata_q;
endmodule
