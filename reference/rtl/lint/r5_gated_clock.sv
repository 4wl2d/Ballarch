// Negative control for R5: the registers are clocked by an internal wire named
// clk that the request gates, so the request clocks the output register. The
// check must look for the clk input port, not for a wire of that name.
// EXPECT: Assertion failed.*i:clk
module r5_gated_clock (
    input  logic clk_in,
    input  logic rst_n,
    input  logic req_valid,
    input  logic d,
    output logic m_valid
);
    logic clk;
    assign clk = clk_in | req_valid;
    logic q;
    always_ff @(posedge clk or negedge rst_n)
        if (!rst_n) q <= 1'b0;
        else q <= d;
    assign m_valid = q;
endmodule
