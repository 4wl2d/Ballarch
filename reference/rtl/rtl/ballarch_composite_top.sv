`timescale 1ns / 1ps

// The composite Ball over its port: ballarch_composite drives the issuer's
// upstream, and the issuer drives the bus. Each block is a Ball to the one
// above it (Core §7.4, §11), and the structural checks apply to each block
// and to this hierarchy.
module ballarch_composite_top #(
    parameter int TIMEOUT = 12,
    parameter int MAX_ATTEMPTS = 2,
    parameter int CMD_W = 4,
    parameter int RESP_WINDOW = 4 * (2 ** CMD_W) - 1
) (
    input  logic       clk,
    input  logic       rst_n,
    input  logic       op_valid,
    output logic       op_ready,
    input  logic [7:0] op_data,
    output logic       op_cmp_valid,
    output logic [1:0] op_cmp_kind,
    output logic [1:0] k_all,
    output logic [1:0] k_any,
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
    logic req_valid, req_ready, cmp_valid;
    logic [1:0] req_op, cmp_kind;
    logic [7:0] req_data;
    /* verilator lint_off UNUSEDSIGNAL */
    logic [7:0] cmp_data;   // the composite does not use the FIRE or CONFIG answer's data
    /* verilator lint_on UNUSEDSIGNAL */

    ballarch_composite u_composite (
        .clk, .rst_n,
        .op_valid, .op_ready, .op_data, .op_cmp_valid, .op_cmp_kind, .k_all, .k_any,
        .req_valid, .req_ready, .req_op, .req_data, .cmp_valid, .cmp_kind
    );

    ballarch_issuer #(
        .TIMEOUT(TIMEOUT), .MAX_ATTEMPTS(MAX_ATTEMPTS), .CMD_W(CMD_W), .RESP_WINDOW(RESP_WINDOW)
    ) u_issuer (
        .clk, .rst_n,
        .req_valid, .req_ready, .req_op, .req_data, .cmp_valid, .cmp_kind, .cmp_data,
        .m_valid, .m_ready, .m_op, .m_data, .m_id, .s_valid, .s_id, .s_err, .s_data
    );
endmodule
