/* Not a purity fixture: fails to compile for an unrelated reason, so the "does not compile" control must name the diagnostic it expects. */
#include "ballarch.h"

int broken(void) { return 0 }
