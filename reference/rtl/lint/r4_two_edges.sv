// Negative control for R4: two registers commit at different edges of clk, so
// one decision's state is not committed at once.
// EXPECT: Assertion failed.*i:clk
module r4_two_edges (
    input  logic clk,
    input  logic rst_n,
    input  logic d,
    output logic m_valid,
    output logic m_other
);
    logic p, n;
    always_ff @(posedge clk or negedge rst_n)
        if (!rst_n) p <= 1'b0;
        else p <= d;
    always_ff @(negedge clk or negedge rst_n)
        if (!rst_n) n <= 1'b0;
        else n <= d;
    assign m_valid = p;
    assign m_other = n;
endmodule
