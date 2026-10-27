/* A board header that marks itself as a system header, as CMSIS headers do; the purity check still reads it. */
#pragma GCC system_header
#include <stdint.h>

static inline void led_on(uint32_t v)
{
    *(volatile uint32_t *)0x40020018u = v;
    __asm__ volatile("" ::: "memory");
}
