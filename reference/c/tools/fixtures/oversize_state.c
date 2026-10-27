/* Negative control for BA_STATE_FITS: a state type larger than the record holds must not compile. */
#include "ballarch.h"

typedef struct {
    uint8_t bytes[BA_MAX_STATE + 8u];
} too_big;

BA_STATE_FITS(too_big);
