# Registration Platform

A Client/Server system simulating registration and active-state maintenance of up to 1 million clients, communicating over a custom TCP-based protocol. Two interchangeable Server implementations exist — see Distributed Server and Centralized Server.

## Language

### Core

**Client**:
A process that registers with the Server and periodically sends a Renewal before its Registration's Validity Period lapses. Holds no persistent connection and no server-assigned state — each Register or Renewal is a short-lived connection: connect, send, receive response, disconnect.
_Avoid_: Session

**Client ID**:
A 12-character numeric string, chosen by the Client itself, that identifies it uniquely across reconnects. Not assigned by the Server.
_Avoid_: Session ID, Client Name

**Server**:
The logical role that accepts Client registrations and maintains their active state, speaking the same wire protocol regardless of which implementation is running. Two interchangeable implementations exist: Distributed Server and Centralized Server. A Client cannot tell which one it's talking to.
_Avoid_: Node, Instance, Pod (when referring to the logical role as a whole)

**Distributed Server**:
The Server implementation that runs as a fleet of stateless pod replicas behind a Kubernetes Horizontal Pod Autoscaler (module `server-distribute`, aggregator artifact `dserver`, running artifact `registration-service`). Any pod can service any Client's call, because Registration state is shared across pods via Redis (ADR-0002) rather than held in pod memory. Expiration is driven entirely by Redis's own key TTL.
_Avoid_: server-distribute (when naming the concept rather than the module)

**Centralized Server**:
The Server implementation that runs as a single node holding Registration state in local process memory (module `server`, artifact `com.registration.server`). Targets the same 1-million-Client scale as the Distributed Server, but on one node rather than a horizontally-scaled fleet — a single-threaded NIO Selector event loop still multiplexes the connections (ADR-0001 applies to both implementations). Expiration is driven by a dedicated reaper thread that periodically scans for lapsed Registrations, since there's no Redis TTL to rely on.
_Avoid_: server (when naming the concept rather than the module), standalone server

**Registration**:
A Client's claim, held by the Server, that it is active. Valid only for its Validity Period unless renewed. Created only by the Client sending a register message; never created or extended by the Server on its own initiative.
_Avoid_: Session, Connection

**Validity Period**:
The duration for which a Registration remains valid without being renewed. Set by the Server when a Registration is created or renewed.
_Avoid_: Lease, TTL, Timeout

**Renewal**:
A message a Client sends before its Validity Period lapses, extending its Registration for another Validity Period. Uses the same Client ID as the original Registration.

**Expiration**:
The Server's removal of a Client's Registration after its Validity Period lapses with no Renewal — never driven by any Client message. The mechanism differs by implementation: Redis key TTL for the Distributed Server, a dedicated reaper thread for the Centralized Server. Distinct from Cancellation, which is Client-initiated.

**Cancellation**:
A Client's voluntary request to remove its own Registration before its Validity Period lapses (ADR-0004). Distinct from Expiration: Cancellation is Client-driven and immediate; Expiration is Server-driven and timeout-based.
_Avoid_: Unregister, Deregister

### Client Simulator

**Simulated Client**:
One independently-running instance of the Client state machine (its own Client ID, its own Registration lifecycle) inside the Client Simulator tool. Normal Mode runs exactly one; Benchmark Mode runs many concurrently, one per virtual thread (ADR-0006).

**Normal Mode**:
The Client Simulator runs a single Simulated Client indefinitely, for manual testing against a real Server.
_Avoid_: Standalone mode

**Benchmark Mode**:
The Client Simulator runs a configured number of Simulated Clients concurrently, ramping up Register calls at a configured rate, and reports aggregate statistics across all of them.

**Renewal Window**:
The `[min%, max%]` range of a Registration's Validity Period within which a Simulated Client schedules its next Renewal. Drawn once, uniformly at random, right after the Client learns its current Validity Period — not re-randomized on every tick. Exists to avoid many Simulated Clients renewing in lockstep against the Server.
