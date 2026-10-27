// Bench model of the bus and the device behind it: the ground truth of the
// benches, not part of the design.
//
// Attempts are counted from the bus itself (handshakes and withdrawn offers),
// never from the issuer's tags, and each command's n-th attempt meets the fate
// the bench set for it (tb_pkg). The device accepts an offer in its first
// cycle, except under F_STALL (never) and F_HOLD (only once the offer has
// waited TIMEOUT cycles, in the last cycle the issuer may hold it). It applies
// a request when it accepts it. It applies a CONFIG only if it has not applied
// a CONFIG with the same command id (the tag's upper CMD_W bits) within the
// last RESP_WINDOW cycles; a duplicate is answered like the original.
//
// Responses go out one per cycle, each within RESP_WINDOW cycles of its
// handshake (the bus contract), and each in the range of edges that keeps its
// fate's meaning: in time (by the issuer's deadline, TIMEOUT + 1 edges after
// the handshake) for F_OK, F_REFUSE, F_FAST and F_DEADLINE, after it for
// F_LATE and F_SLOW. Within that range each aims at an edge: F_FAST the first
// after the handshake, F_DEADLINE the deadline itself, F_SLOW the window's
// last (or the one before, if an answer in time needs the last). When two
// answers are due at one edge, one that cannot wait goes first, then one aimed
// at that edge, then the one with the earlier bound; an answer that could not
// wait yields by going a cycle earlier when that is foreseen. A correct issuer
// has only one answer in time outstanding, so the F_FAST and F_DEADLINE edges
// are kept (the benches report how often they were not); the model stops the
// run if it could not keep an answer within its range.
//
// Edge numbers: `cyc` is the number of the current clock edge as seen by
// processes at that edge; a response due at edge t is driven from the
// preceding falling edge and sampled at edge t.
`timescale 1ns / 1ps
module tb_device
    import tb_pkg::*;
#(
    parameter int TIMEOUT = 12,
    parameter int CMD_W = 4,
    parameter int ID_W = 6,
    parameter int RESP_WINDOW = 63
) (
    input  logic            clk,
    input  logic            cmd_accept,   // the issuer accepts a command at this edge
    input  logic            m_valid,
    output logic            m_ready,
    input  logic [1:0]      m_op,
    input  logic [7:0]      m_data,
    input  logic [ID_W-1:0] m_id,
    output logic            s_valid,
    output logic [ID_W-1:0] s_id,
    output logic            s_err,
    output logic [7:0]      s_data
);
    localparam int MAXC = 80;    // commands per scenario
    localparam int MAXA = 8;     // attempts per command with a fate of their own
    localparam int QN = 16;      // responses in flight
    localparam int LOGN = 512;   // handshakes per scenario

    int cyc = 0;
    int fate[0:MAXC-1][0:MAXA-1];

    // The command in flight and its finished offers (handshakes or withdrawals).
    int cmd;
    int att;
    bit offer_open;
    int offer_edges;                 // edges at which the open offer waited

    // Ground truth and evidence, per command.
    int handshakes[0:MAXC-1];
    int withdrawals[0:MAXC-1];
    int undefined_offers[0:MAXC-1];
    int config_applies[0:MAXC-1];
    int fires[0:MAXC-1];
    bit ok_latest[0:MAXC-1];         // an OK answered the command's latest accepted attempt
    logic [7:0] ok_data[0:MAXC-1];   // the data of that answer
    logic [7:0] config_value;        // the device's configuration register
    int reads;
    int cfg_applied_at[0:(2**CMD_W)-1];

    // Every handshake's edge and tag, for the tag-window check.
    int log_n;
    int log_edge[0:LOGN-1];
    logic [ID_W-1:0] log_tag[0:LOGN-1];
    int tag_reuses;
    string tag_reuse_msg;

    // Responses in flight.
    bit q_live[0:QN-1];
    bit q_late[0:QN-1];              // released in the cycle after the next handshake
    int q_lo[0:QN-1];                // the first edge it may arrive and keep its fate's meaning
    int q_due[0:QN-1];               // the edge it aims at, while it is not pushed
    bit q_exact[0:QN-1];             // the aimed edge is part of the fate (F_FAST, F_DEADLINE)
    int q_bound[0:QN-1];             // the last edge it may arrive and keep its fate's meaning
    int q_hs[0:QN-1];                // its handshake edge
    int q_cmd[0:QN-1];
    int q_att[0:QN-1];
    logic [ID_W-1:0] q_id[0:QN-1];
    logic q_err[0:QN-1];
    logic [7:0] q_data[0:QN-1];
    int drv;                         // the response driven for the coming edge, or -1
    int missed_edges;                // answers aimed at an edge that went at another
    int latest_att[0:MAXC-1];        // attempt index of the command's latest handshake

    task automatic reset_model();
        cmd = -1;
        att = 0;
        offer_open = 0;
        offer_edges = 0;
        config_value = 8'h11;
        reads = 0;
        log_n = 0;
        tag_reuses = 0;
        tag_reuse_msg = "";
        drv = -1;
        missed_edges = 0;
        for (int c = 0; c < MAXC; c++) begin
            for (int a = 0; a < MAXA; a++) fate[c][a] = F_OK;
            handshakes[c] = 0;
            withdrawals[c] = 0;
            undefined_offers[c] = 0;
            config_applies[c] = 0;
            fires[c] = 0;
            ok_latest[c] = 0;
            ok_data[c] = 8'h00;
            latest_att[c] = -1;
        end
        for (int i = 0; i < 2**CMD_W; i++) cfg_applied_at[i] = -1000000;
        for (int i = 0; i < QN; i++) q_live[i] = 0;
        q_count = 0;
    endtask

    // The fate of attempt `a` of command `c`; attempts beyond the table meet its last entry.
    function automatic int fate_of(input int c, input int a);
        if (c < 0 || c >= MAXC) return F_OK;
        return fate[c][a < MAXA ? a : MAXA - 1];
    endfunction

    int q_count;                     // responses in flight
    function automatic int queue_size();
        return q_count;
    endfunction

    // An answer that cannot wait past edge d (its bound) and is due there gives
    // the edge to another answer that must go at d, by going one cycle earlier
    // if its range allows.
    task automatic yield_edge(input int d, input int now);
        for (int i = 0; i < QN; i++)
            if (q_live[i] && q_due[i] == d && q_bound[i] == d && d - 1 > now && d - 1 >= q_lo[i]) begin
                q_due[i] = d - 1;
                if (q_exact[i]) missed_edges++;
            end
    endtask

    task automatic enqueue(input int f, input logic [7:0] data, input int now);
        int slot;
        bit in_time;
        in_time = f == F_OK || f == F_REFUSE || f == F_FAST || f == F_DEADLINE;
        slot = -1;
        for (int i = 0; i < QN; i++) if (!q_live[i] && slot < 0) slot = i;
        if (slot < 0) $fatal(1, "tb_device: response queue full");
        q_lo[slot] = in_time ? now + 1 : now + TIMEOUT + 2;
        q_bound[slot] = in_time ? now + TIMEOUT + 1 : now + RESP_WINDOW;
        q_due[slot] = f == F_FAST ? now + 1 : f == F_DEADLINE ? now + TIMEOUT + 1 : in_time ? now + 2 : now + RESP_WINDOW;
        q_exact[slot] = f == F_FAST || f == F_DEADLINE;
        if (q_bound[slot] < q_lo[slot]) $fatal(1, "tb_device: RESP_WINDOW leaves no room for a late answer");
        if (in_time) yield_edge(q_due[slot], now);
        q_live[slot] = 1;
        q_count++;
        q_late[slot] = f == F_LATE;
        q_hs[slot] = now;
        q_cmd[slot] = cmd;
        q_att[slot] = att;
        q_id[slot] = m_id;
        q_err[slot] = f == F_REFUSE;
        q_data[slot] = data;
    endtask

    // Which of two answers due at edge e goes first.
    function automatic bit goes_first(input int i, input int j, input int e);
        if ((q_bound[i] == e) != (q_bound[j] == e)) return q_bound[i] == e;     // cannot wait
        if ((q_exact[i] && q_due[i] == e) != (q_exact[j] && q_due[j] == e))     // aimed at e
            return q_exact[i] && q_due[i] == e;
        return q_bound[i] < q_bound[j];
    endfunction

    task automatic handshake(input int now);
        int f;
        int id;
        bit dup;
        logic [7:0] data;
        f = fate_of(cmd, att);
        // Tag window: no tag may recur while a response to its earlier use may still arrive.
        for (int i = 0; i < log_n; i++) begin
            if (log_tag[i] == m_id && now - log_edge[i] <= RESP_WINDOW) begin
                if (tag_reuses == 0)
                    tag_reuse_msg = $sformatf("tag %0h sent at edges %0d and %0d, within RESP_WINDOW=%0d",
                                              m_id, log_edge[i], now, RESP_WINDOW);
                tag_reuses++;
            end
        end
        if (log_n < LOGN) begin
            log_edge[log_n] = now;
            log_tag[log_n] = m_id;
            log_n++;
        end
        // A late answer waiting for a later handshake goes out in the next cycle.
        for (int i = 0; i < QN; i++)
            if (q_live[i] && q_late[i] && q_hs[i] < now && now + 1 < q_due[i]) q_due[i] = now + 1;
        if (cmd >= 0) begin
            handshakes[cmd]++;
            ok_latest[cmd] = 0;
            latest_att[cmd] = att;
        end
        // The effect.
        if (applies(f) && cmd >= 0) begin
            case (m_op)
                OP_CONFIG: begin
                    id = int'(m_id >> (ID_W - CMD_W));
                    dup = now - cfg_applied_at[id] <= RESP_WINDOW;
                    if (!dup) begin
                        config_applies[cmd]++;
                        config_value = m_data;
                        cfg_applied_at[id] = now;
                    end
                end
                OP_FIRE: fires[cmd]++;
                default: ;
            endcase
        end
        if (m_op == OP_READ) reads++;
        data = m_op == OP_READ ? 8'hA0 + 8'(reads) : config_value;
        // The answer.
        case (f)
            F_OK, F_REFUSE, F_LATE, F_SLOW, F_FAST, F_DEADLINE: enqueue(f, data, now);
            default: ;                       // F_LOST, F_VANISH, F_HOLD: no answer
        endcase
        att++;
        offer_open = 0;
        offer_edges = 0;
    endtask

    // Between edges: decide whether the offered attempt is accepted at the coming
    // edge, and drive at most one response, chosen by goes_first.
    always @(negedge clk) begin
        int best, f;
        bit accept;
        f = fate_of(cmd, att);
        accept = m_valid && f != F_STALL && (f != F_HOLD || offer_edges == TIMEOUT);
        m_ready <= accept;
        // Accepted under F_FAST at the coming edge, its answer is due at the edge after.
        if (accept && f == F_FAST) yield_edge(cyc + 1, cyc - 1);
        s_valid <= 1'b0;
        best = -1;
        for (int i = 0; i < QN; i++)
            if (q_live[i] && q_due[i] <= cyc && (best < 0 || goes_first(i, best, cyc))) best = i;
        drv = best;
        if (best >= 0) begin
            s_valid <= 1'b1;
            s_id <= q_id[best];
            s_err <= q_err[best];
            s_data <= q_data[best];
            for (int i = 0; i < QN; i++) begin
                if (i != best && q_live[i] && q_due[i] <= cyc) begin
                    if (q_exact[i] && q_due[i] == cyc) missed_edges++;
                    q_due[i] = cyc + 1;
                    if (q_due[i] > q_bound[i]) $fatal(1, "tb_device: could not keep an answer's fate");
                end
            end
        end
    end

    // At each edge: the response sampled now, a newly accepted command, and the bus offer.
    always @(posedge clk) begin
        int now;
        now = cyc;
        cyc <= cyc + 1;
        if (drv >= 0) begin
            if (!q_err[drv] && q_cmd[drv] >= 0 && q_att[drv] == latest_att[q_cmd[drv]]) begin
                ok_latest[q_cmd[drv]] = 1;
                ok_data[q_cmd[drv]] = q_data[drv];
            end
            q_live[drv] = 0;
            q_count--;
            drv = -1;
        end
        if (cmd_accept) begin
            cmd++;
            att = 0;
            offer_open = 0;
            offer_edges = 0;
            if (cmd >= MAXC) $fatal(1, "tb_device: too many commands in one scenario");
        end
        if (m_valid) begin
            if (cmd >= 0 && m_op == OP_UNDEFINED) undefined_offers[cmd]++;
            if (m_ready) handshake(now);
            else begin
                offer_open = 1;
                offer_edges++;
            end
        end else if (offer_open) begin
            if (cmd >= 0) withdrawals[cmd]++;
            att++;
            offer_open = 0;
            offer_edges = 0;
        end
    end
endmodule
