// Negative control for R2: the next state is not assigned on every path of an
// always_comb, so synthesis infers a latch, which is state that no clock edge commits.
// EXPECT: Latch inferred
module r2_latch_always_comb (
    input  logic clk,
    input  logic go,
    output logic busy
);
    logic busy_q, busy_d;
    always_comb begin
        if (go) busy_d = 1'b1;  // no default: busy_d keeps its old value when !go
    end
    always_ff @(posedge clk) busy_q <= busy_d;
    assign busy = busy_q;
endmodule
