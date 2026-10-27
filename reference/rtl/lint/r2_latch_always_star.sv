// Negative control for R2: an incomplete always @* infers a latch without the
// error that always_comb gives.
// EXPECT: Assertion failed.*dlatch
module r2_latch_always_star (
    input  logic clk,
    input  logic go,
    input  logic d,
    output logic busy
);
    logic busy_q, busy_d;
    always @* begin
        if (go) busy_d = d;
    end
    always_ff @(posedge clk) busy_q <= busy_d;
    assign busy = busy_q;
endmodule
