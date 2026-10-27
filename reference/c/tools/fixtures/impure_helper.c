/* Negative control for check_purity.sh --uses: a "helper" that keeps a hidden counter. */
#include "ballarch.h"

int helper_count(int x);

static int calls;

int helper_count(int x) { return x + ++calls; }
