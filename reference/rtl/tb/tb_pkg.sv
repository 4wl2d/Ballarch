// Shared definitions of the benches: operations, outcome kinds, the fates a
// bus attempt can meet, the classification of Core §3.2, and the knowledge
// algebra of Core §4.2 computed by supervaluation (independently of the
// Kleene tables in the design).
`timescale 1ns / 1ps
package tb_pkg;
    localparam logic [1:0] OP_READ = 2'd0, OP_CONFIG = 2'd1, OP_FIRE = 2'd2, OP_UNDEFINED = 2'd3;
    localparam logic [1:0] DONE = 2'd1, NOT_DONE = 2'd2, UNKNOWN = 2'd3;
    localparam logic [1:0] K_YES = 2'b10, K_NO = 2'b01, K_MAYBE = 2'b11;

    // Fates of one bus attempt. "Applied" means the device took the effect
    // (for CONFIG, unless it is a duplicate of a command id it already applied).
    // An answer "in time" arrives by the issuer's deadline, TIMEOUT + 1 edges
    // after the handshake; a late one arrives after it and within the window.
    localparam int F_OK = 0;        // accepted, applied, OK answered in time, 2 edges after the handshake
    localparam int F_LATE = 1;      // accepted, applied, OK answered late: in the cycle after the next
                                    // handshake on the bus, or at the window's end
    localparam int F_LOST = 2;      // accepted, applied, the answer is lost
    localparam int F_VANISH = 3;    // accepted, neither applied nor answered
    localparam int F_REFUSE = 4;    // accepted, refused without effect, ERR answered in time
    localparam int F_STALL = 5;     // never accepted
    localparam int F_FAST = 6;      // accepted, applied, OK answered at the first edge after the handshake
    localparam int F_DEADLINE = 7;  // accepted, applied, OK answered at the deadline, the last edge in time
    localparam int F_HOLD = 8;      // accepted only in the offer's last cycle (after TIMEOUT cycles of
                                    // waiting), applied, the answer is lost
    localparam int F_SLOW = 9;      // accepted, applied, OK answered at the end of the response window
    localparam int MAIN_FATES = 9;  // the exhaustive exploration uses fates 0..8

    function automatic string fate_name(input int f);
        case (f)
            F_OK: return "OK";
            F_LATE: return "LATE";
            F_LOST: return "LOST";
            F_VANISH: return "VANISH";
            F_REFUSE: return "REFUSE";
            F_STALL: return "STALL";
            F_FAST: return "FAST";
            F_DEADLINE: return "DEADLINE";
            F_HOLD: return "HOLD";
            F_SLOW: return "SLOW";
            default: return "?";
        endcase
    endfunction

    function automatic string kind_name(input logic [1:0] k);
        case (k)
            DONE: return "DONE";
            NOT_DONE: return "NOT_DONE";
            UNKNOWN: return "UNKNOWN";
            default: return "not made";
        endcase
    endfunction

    function automatic string tri_name(input logic [1:0] t);
        case (t)
            K_YES: return "Yes";
            K_NO: return "No";
            K_MAYBE: return "Maybe";
            default: return "invalid";
        endcase
    endfunction

    function automatic bit effectful(input logic [1:0] op);
        return op == OP_CONFIG || op == OP_FIRE;
    endfunction

    function automatic bit applies(input int f);
        return f == F_OK || f == F_LATE || f == F_LOST || f == F_FAST || f == F_DEADLINE || f == F_HOLD
            || f == F_SLOW;
    endfunction

    // The outcome Core §3.2 requires for a command whose attempts meet the
    // given fates: the reference against which the issuer's choice is checked.
    function automatic logic [1:0] expected_kind(input logic [1:0] op, input int f0, input int f1,
                                                 input int max_attempts);
        int f;
        if (op == OP_UNDEFINED) return NOT_DONE;       // refused without being sent
        for (int k = 1; k <= max_attempts; k++) begin
            f = k == 1 ? f0 : f1;                      // attempts after the second meet the second's fate
            case (f)
                F_STALL: return (k == 1 || op == OP_READ) ? NOT_DONE : UNKNOWN;
                F_OK, F_FAST, F_DEADLINE: return DONE;
                F_REFUSE: return (k == 1 || op == OP_READ) ? NOT_DONE : UNKNOWN;
                default: begin                         // no answer by the deadline (LATE, LOST, VANISH, HOLD, SLOW)
                    if (!(op == OP_READ || op == OP_CONFIG) || k == max_attempts)
                        return op == OP_READ ? NOT_DONE : UNKNOWN;
                end
            endcase
        end
        return 2'd0;
    endfunction

    // The truth values one outcome leaves possible for its call's effect:
    // bit 1 = may be true, bit 0 = may be false. A call never made is false.
    function automatic logic [1:0] possible(input logic [1:0] kind, input bit made);
        if (!made) return 2'b01;
        case (kind)
            DONE: return 2'b10;
            NOT_DONE: return 2'b01;
            default: return 2'b11;
        endcase
    endfunction

    // Supervaluation over every world consistent with the two constituents:
    // Yes if the formula holds in all of them, No if in none, otherwise Maybe.
    function automatic logic [1:0] superval(input logic [1:0] pa, input logic [1:0] pb, input bit is_all);
        bit some_true, some_false, v;
        some_true = 0;
        some_false = 0;
        for (int a = 0; a <= 1; a++)
            for (int b = 0; b <= 1; b++)
                if (pa[a == 1 ? 1 : 0] && pb[b == 1 ? 1 : 0]) begin
                    v = is_all ? (a == 1 && b == 1) : (a == 1 || b == 1);
                    if (v) some_true = 1; else some_false = 1;
                end
        return some_true && some_false ? K_MAYBE : some_true ? K_YES : K_NO;
    endfunction
endpackage
