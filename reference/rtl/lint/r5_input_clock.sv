// Negative control for R5: an input used as a clock commits state at an instant
// that is not the block's clock edge.
// EXPECT: Assertion failed.*i:clk
module r5_input_clock (
    input  logic clk,
    input  logic rst_n,
    input  logic req_valid,
    output logic m_valid
);
    logic q, r;
    always_ff @(posedge req_valid or negedge rst_n)
        if (!rst_n) q <= 1'b0;
        else q <= 1'b1;
    always_ff @(posedge clk) r <= q;
    assign m_valid = r;
endmodule
