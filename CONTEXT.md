# Registration Platform

A Client/Server system simulating registration and active-state maintenance of up to 1 million clients, designed to run as a stateless, horizontally-scaled fleet behind a Kubernetes Horizontal Pod Autoscaler, communicating over a custom TCP-based protocol.

## Language

### Core

**Client**:
A process that registers with the Server and periodically sends a Renewal before its Registration's Validity Period lapses. Holds no persistent connection and no server-assigned state — each Register or Renewal is a short-lived connection: connect, send, receive response, disconnect.
_Avoid_: Session

**Client ID**:
A 12-character numeric string, chosen by the Client itself, that identifies it uniquely across reconnects. Not assigned by the Server.
_Avoid_: Session ID, Client Name

**Server**:
The logical service that accepts Client registrations and maintains their active state — implemented as a fleet of stateless pod replicas behind a Kubernetes Horizontal Pod Autoscaler. Any pod can service any Client's Register or Renewal call, because Registration state is shared across pods via Redis rather than held in pod memory.
_Avoid_: Node, Instance, Pod (when referring to the logical service as a whole)

**Registration**:
A Client's claim, held by the Server, that it is active. Valid only for its Validity Period unless renewed. Created only by the Client sending a register message; never created or extended by the Server on its own initiative.
_Avoid_: Session, Connection

**Validity Period**:
The duration for which a Registration remains valid without being renewed. Set by the Server when a Registration is created or renewed.
_Avoid_: Lease, TTL, Timeout

**Renewal**:
A message a Client sends before its Validity Period lapses, extending its Registration for another Validity Period. Uses the same Client ID as the original Registration.

**Expiration**:
The Server's removal of a Client's Registration after its Validity Period lapses with no Renewal — driven entirely by Redis's own key TTL, not by any Client message. Distinct from Cancellation, which is Client-initiated.

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
