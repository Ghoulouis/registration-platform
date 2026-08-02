# Binary message format: fixed header, packed Client ID, relative Validity Period

Client and Server exchange four message kinds over short-lived TCP connections: `REGISTER`, `RENEW`, and their responses. We chose a binary encapsulation — a fixed-size header (message type + payload length) followed by a fixed-layout payload — over a text format like JSON, since it pairs directly with NIO's `ByteBuffer`-based reads and avoids delimiter-scanning across partial reads.

Within the payload, the Client ID (a 12-digit numeric string at the domain level) is packed as a binary integer rather than sent as 12 ASCII bytes, trading 4 bytes per message for a smaller wire size; the codec zero-pads back to a 12-character string on display, so no information is lost as long as that convention holds. The Validity Period is returned as a relative duration in seconds (fits in 2 bytes; range is 30–300s) rather than an absolute timestamp, so Client and Server never need synchronized clocks.

`REGISTER` and `RENEW` are kept strictly symmetric and non-overlapping: `REGISTER` succeeds only when the Client ID has no live Registration (otherwise `ALREADY_REGISTERED`); `RENEW` succeeds only when it does (otherwise `NOT_REGISTERED`). Neither message silently falls back to the other's behavior.
