/* Binds the transfer decider into a Ball type. Kept apart from transfer.c so that the decider object holds no relocated data. */
#include "transfer.h"

const ba_ball tf_ball = {"transfer", 1u, sizeof(tf_state), TF_PORTS, tf_effects, tf_initial, tf_step};
