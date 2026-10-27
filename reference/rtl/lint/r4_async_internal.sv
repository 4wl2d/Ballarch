// Negative control for R4: the request sets an internal register asynchronously.
// No output follows the request between edges (the register reaches the output
// through another one), but the block's state changes between clock edges.
// EXPECT: Assertion failed.*@through_clocked @ffs
module r4_async_internal (
    input  logic clk,
    input  logic rst_n,
    input  logic req_valid,
    input  logic d,
    output logic m_valid
);
    logic q, r;
    always_ff @(posedge clk or posedge req_valid)
        if (req_valid) q <= 1'b1;
        else q <= d;
    always_ff @(posedge clk or negedge rst_n)
        if (!rst_n) r <= 1'b0;
        else r <= q;
    assign m_valid = r;
endmodule
