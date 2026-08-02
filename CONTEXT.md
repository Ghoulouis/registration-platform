# Registration Platform

A Client/Server system simulating registration and active-state maintenance of up to 1 million clients, communicating over a custom TCP-based protocol. Two interchangeable Server implementations exist — see Distributed Server and Centralized Server.

## Language

### Core

**Client**:
A process that registers with the Server and periodically sends a Renewal before its Registration's Validity Period lapses. Holds no persistent connection — each Renewal or Cancellation is one short-lived connection: connect, send, receive response, disconnect. A Register attempt is two such connections in sequence (get a Nonce to sign, then submit a Nonce Signature), never one held open across both (ADR-0009). The Server does hold state tied to the Client between calls (the current Nonce, ADR-0010/0011) — but that's server-assigned state, not a persistent connection or anything resembling a Session.
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
A message a Client sends before its Validity Period lapses, extending its Registration for another Validity Period. Uses the same Client ID as the original Registration, and must carry a Nonce Signature proving the Client holds the Shared Signing Key (ADR-0010).

**Expiration**:
The Server's removal of a Client's Registration after its Validity Period lapses with no Renewal — never driven by any Client message. The mechanism differs by implementation: Redis key TTL for the Distributed Server, a dedicated reaper thread for the Centralized Server. Distinct from Cancellation, which is Client-initiated.

**Cancellation**:
A Client's voluntary request to remove its own Registration before its Validity Period lapses (ADR-0004), authenticated the same way as a Renewal — a Nonce Signature (ADR-0010). Distinct from Expiration: Cancellation is Client-driven and immediate; Expiration is Server-driven and timeout-based.
_Avoid_: Unregister, Deregister

**Shared Signing Key**:
The single Ed25519 keypair configured for a Client Simulator run: every Simulated Client signs its Nonce with the same private key, and the Server verifies every Nonce Signature against the same public key. Not a per-Client credential — this simulates the mechanism, not a multi-tenant identity system (ADR-0009). The same keypair authenticates Register, Renewal, and Cancellation (ADR-0011).

**Nonce**:
A random 32-byte value the Server holds per Client ID, one record spanning two lifecycle phases (ADR-0011): PENDING (issued for an unconfirmed Register attempt, short-lived, discarded after any verification attempt) and CONFIRMED (tied to a live Registration, replaced by a new one on every successful Renewal, discarded on Cancellation or Expiration). A Register, Renewal, or Cancellation must carry a Nonce Signature proving the Client holds the Shared Signing Key.
_Avoid_: Challenge (an earlier, separate concept for the PENDING phase only — merged into Nonce by ADR-0011)

**Nonce Signature**:
The Ed25519 signature over the current Nonce, computed by the Client with the Shared Signing Key's private key, submitted as the authentication credential for the second step of Register, a Renewal, or a Cancellation. Verified against the Shared Signing Key's public key.
_Avoid_: Response (ambiguous with the many `*Response` protocol messages), Signature (use in implementation, not as the glossary term)

### Observability

**Trace ID**:
A 16-byte identifier for one logical Register, Renewal, or Cancellation attempt as `RetryingRequester` sees it (ADR-0012, W3C Trace Context). Generated once per `register()`/`renew()`/`send()` call and shared across every retry and, for Register, both of its two legs — so every log line produced while working on that one attempt can be tied back together.

**Span ID**:
An 8-byte identifier for one specific connection attempt within a Trace (ADR-0012). The Client generates a fresh Span ID for every attempt — each retry, each Register leg — while the Trace ID stays constant. The Server logs using the Span ID it received rather than minting its own child span; there's no further downstream hop in this system to justify one.
_Avoid_: Correlation ID (the generic term; this project uses the W3C Trace Context vocabulary specifically, for interop with standard observability tooling)

### Client Simulator

**Simulated Client**:
One independently-running instance of the Client state machine (its own Client ID, its own Registration lifecycle) inside the Client Simulator tool. Normal Mode runs exactly one; Benchmark Mode runs many concurrently, one per virtual thread (ADR-0006).

**Normal Mode**:
The Client Simulator runs a single Simulated Client indefinitely, for manual testing against a real Server.
_Avoid_: Standalone mode

**Benchmark Mode**:
The Client Simulator runs a configured number of Simulated Clients concurrently, ramping up Register calls at a configured rate, and reports aggregate statistics across all of them for a configured Benchmark Duration, after which it runs the same graceful shutdown as a Ctrl+C (every Simulated Client sends its voluntary Cancellation per ADR-0004, final stats are printed) and the process exits on its own.

**Benchmark Duration**:
The configured length of time a Client Simulator in Benchmark Mode runs before self-terminating. Distinct from Normal Mode, which always runs until killed.

**Renewal Window**:
The `[min%, max%]` range of a Registration's Validity Period within which a Simulated Client schedules its next Renewal. Drawn once, uniformly at random, right after the Client learns its current Validity Period — not re-randomized on every tick. Exists to avoid many Simulated Clients renewing in lockstep against the Server.

### Benchmark Harness

**Benchmark Harness**:
The Python tool (`benchmark/benchmark.py`) that orchestrates a full Benchmark Run: starts a Server, starts a Client Simulator in Benchmark Mode against it, profiles both processes' hardware resource usage (CPU, RAM) throughout, and produces a report. Distinct from Benchmark Mode, which is the Client-side load-generation behavior the Harness merely invokes.
_Avoid_: benchmark script, load test (when referring to the tool itself)

**Benchmark Run**:
One execution of the Benchmark Harness, configured by a Resource Profile and a Load Profile: start Server, wait (Idle), start Client in Benchmark Mode, sample both processes until the Client self-terminates after its Benchmark Duration (Active), wait, stop Server, then generate the report.

**Idle** (Server phase):
The Benchmark Run phase, measured during the pre-Client wait, where Server resource usage is sampled with no Client load applied. Used as the baseline in the report.

**Active** (Benchmark Run phase):
The Benchmark Run phase, spanning the Client Simulator's actual process lifetime (started, sampled until it exits after its Benchmark Duration elapses and self-terminates), where both Server and Client resource usage are sampled under load. Bounded by the Client's own Benchmark Duration setting rather than an independent Harness timer, so the two can't drift out of sync.
_Avoid_: ACTIVE (as a bare stats variable with no defined sampling window — the earlier draft left this uncomputed)

**Resource Profile**:
The CPU/RAM limit applied independently to the Server process and the Client process for a Benchmark Run (default: 2 cores / 4GB each), enforced as a hard cap by running each process in its own Docker container (ADR-0008) rather than a JVM-level hint. A configuration setting in the Benchmark Harness script, not a CLI flag — changing it means editing the script's config values.

**Load Profile**:
The simulated Client count (default 10,000), Register rate (default 1,000/s), and Benchmark Duration (default 60s) configured for a Benchmark Run and passed through to the Client Simulator's CLI flags. Unlike Resource Profile, exposed as CLI flags on the Benchmark Harness itself so it can be varied per run without editing the script. Other Client-side settings (Renewal Window, timeouts, retries, host/port) stay at their Client-side defaults and aren't part of the Load Profile.

**Benchmark Report**:
The output of a Benchmark Run: CPU and RAM usage charts (saved as image files) for the Server across its Idle and Active phases, and for the Client across its Active phase.
