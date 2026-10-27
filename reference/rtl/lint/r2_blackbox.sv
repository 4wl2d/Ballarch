// Negative control for R2: a black box between registers may hold any state (a
// latch, a register on another clock), and no path from an input to an output
// runs through it. Only a check that admits no cell it cannot see into rejects it.
// EXPECT: Assertion failed.*c:\* @comb
(* blackbox *)
module r2_blackbox_cell (
    input  logic en,
    input  logic d,
    output logic q
);
endmodule

module r2_blackbox (
    input  logic clk,
    input  logic rst_n,
    input  logic go,
    input  logic d,
    output logic q
);
    logic e, x, l, r;
    always_ff @(posedge clk) begin
        e <= go;
        x <= d;
    end
    r2_blackbox_cell u_cell (.en(e), .d(x), .q(l));
    always_ff @(posedge clk) r <= l;
    assign q = r;
endmodule
