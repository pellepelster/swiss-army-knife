/* the canary of sakctl's first check: a container runtime that can pull and run this works */
#include <stdio.h>

int main(void) {
    puts("sak-cli-docker-canary");
    return 0;
}
