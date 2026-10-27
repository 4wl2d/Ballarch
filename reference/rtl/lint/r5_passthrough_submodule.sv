// Negative control for R5: the path from input to output runs through a
// submodule with no register; the check sees it after flattening.
// EXPECT: Assertion failed.*o:\*
module r5_passthrough_inner (
    input  logic a,
    output logic y
);
    assign y = ~a;
endmodule

module r5_passthrough_submodule (
    input  logic clk,
    input  logic rst_n,
    input  logic req_valid,
    output logic m_valid
);
    r5_passthrough_inner u_inner (.a(req_valid), .y(m_valid));
endmodule
