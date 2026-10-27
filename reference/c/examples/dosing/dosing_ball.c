/* Binds the therapy decider into a Ball type. Kept apart from dosing.c so that the decider object holds no relocated data. */
#include "dosing.h"

const ba_ball th_ball = {"therapy", 1u, sizeof(th_state), TH_PORTS, th_effects, th_initial, th_step};
