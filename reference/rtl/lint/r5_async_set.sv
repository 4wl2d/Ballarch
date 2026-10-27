// Negative control for R5: the request drives an asynchronous set, so the output
// rises with the input before any clock edge commits a decision.
// EXPECT: Assertion failed.*o:\*
module r5_async_set (
    input  logic clk,
    input  logic rst_n,
    input  logic req_valid,
    input  logic m_ready,
    output logic m_valid
);
    logic q;
    always_ff @(posedge clk or posedge req_valid)
        if (req_valid) q <= 1'b1;
        else if (m_ready || !rst_n) q <= 1'b0;
    assign m_valid = q;
endmodule
