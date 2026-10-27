// Negative control for R2 and R4: storage written without a clock edge. A
// memory array written from always @* is state that no clock edge commits, and
// Yosys keeps it as a memory cell, not as a latch cell.
// EXPECT: Assertion failed.*c:\* @comb
module r2_memory_async_write (
    input  logic       clk,
    input  logic       rst_n,
    input  logic       we,
    input  logic [7:0] req_data,
    output logic [7:0] m_data
);
    (* nomem2reg *) logic [7:0] mem [0:3];
    always @* if (we) mem[req_data[1:0]] = req_data;
    assign m_data = mem[0];
endmodule
