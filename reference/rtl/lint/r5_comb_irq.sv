// Negative control for R5: an output with an arbitrary name (not m_* or cmp_*)
// follows an input combinationally; every output is checked.
// EXPECT: Assertion failed.*o:\*
module r5_comb_irq (
    input  logic clk,
    input  logic rst_n,
    input  logic fault,
    output logic irq
);
    logic pending_q;
    always_ff @(posedge clk or negedge rst_n)
        if (!rst_n) pending_q <= 1'b0;
        else pending_q <= fault;
    assign irq = pending_q || fault;
endmodule
