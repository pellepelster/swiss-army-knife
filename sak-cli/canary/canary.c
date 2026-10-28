/* the canary of sakctl's first check: a container runtime that can pull and run this works */
#include <stdio.h>

int main(void) {
    puts("sak-docker-canary");
    return 0;
}
