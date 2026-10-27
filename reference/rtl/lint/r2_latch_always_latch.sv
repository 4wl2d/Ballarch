// Negative control for R2: an explicit latch is state that no clock edge commits,
// even though Verilator and Yosys accept always_latch without complaint.
// EXPECT: Assertion failed.*dlatch
module r2_latch_always_latch (
    input  logic clk,
    input  logic go,
    input  logic d,
    output logic q
);
    logic l, r;
    always_latch if (go) l = d;
    always_ff @(posedge clk) r <= l;
    assign q = r;
endmodule
