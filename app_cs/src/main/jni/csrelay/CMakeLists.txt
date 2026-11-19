# CS Relay tunnel client (pure C, pthreads + sockets). See csrelay.c for why it is native.
add_library(csrelay SHARED csrelay.c)
target_compile_options(csrelay PRIVATE -O2 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(csrelay PRIVATE log)
