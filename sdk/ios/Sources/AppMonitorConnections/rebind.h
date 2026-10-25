// An app linking its own fishhook keeps it: this copy's two functions are renamed.
#define rebind_symbols appmonitor_rebind_symbols
#define rebind_symbols_image appmonitor_rebind_symbols_image
#include "fishhook/fishhook.h"
