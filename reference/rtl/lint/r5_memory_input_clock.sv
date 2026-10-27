// Negative control for R5: a request used as the write clock of a memory. The
// output follows the request's edge, not an edge of clk; a memory write port
// is not a flip-flop, so only a check that rejects memories sees it.
// EXPECT: Assertion failed.*c:\* @comb
module r5_memory_input_clock (
    input  logic       clk,
    input  logic       rst_n,
    input  logic       req_valid,
    input  logic [7:0] req_data,
    output logic [7:0] m_data
);
    logic [7:0] mem [0:3];
    always_ff @(posedge req_valid) mem[req_data[1:0]] <= req_data;
    assign m_data = mem[0];
endmodule
