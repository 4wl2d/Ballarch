// Negative control for R5: the reset may reach registers only; here it also
// drives an output without a register.
// EXPECT: Assertion failed.*o:\*
module r5_reset_to_output (
    input  logic clk,
    input  logic rst_n,
    input  logic d,
    output logic m_valid
);
    logic r;
    always_ff @(posedge clk or negedge rst_n)
        if (!rst_n) r <= 1'b0;
        else r <= d;
    assign m_valid = r && rst_n;
endmodule
