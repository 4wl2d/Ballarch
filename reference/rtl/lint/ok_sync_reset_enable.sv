// Positive control: a correct registered design with a synchronous reset and an
// enable (a $sdffe cell) must pass every structural check.
// EXPECT: pass
module ok_sync_reset_enable (
    input  logic clk,
    input  logic rst_n,
    input  logic en,
    input  logic d,
    output logic m_valid
);
    logic q;
    always_ff @(posedge clk)
        if (!rst_n) q <= 1'b0;
        else if (en) q <= d;
    assign m_valid = q;
endmodule
