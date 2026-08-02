# Client uses one virtual thread per simulated Client, not NIO

The Server uses a single-threaded NIO Selector event loop (ADR-0001) because a single pod must multiplex up to 1 million real concurrent connections. The Client is a different problem: one benchmark process simulates some bounded number of Clients (hundreds to low thousands, not millions), each doing simple sequential blocking I/O — open a connection, write a request, read a response, close.

We chose one Java 21 virtual thread per simulated Client, using ordinary blocking `SocketChannel`/`Socket` calls, over building a second NIO event loop on the Client side. Virtual threads make blocking I/O cheap at this scale, so the NIO Selector's non-blocking-read/partial-frame-buffering complexity (justified server-side by the 1M-connection target) buys nothing here and would just be extra code to maintain for no benefit.
