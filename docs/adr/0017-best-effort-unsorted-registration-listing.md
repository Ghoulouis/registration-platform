# Best-effort, unsorted pagination for the Registration listing endpoint

The Centralized Server gets its first HTTP surface (`spring-boot-starter-web` + springdoc
OpenAPI): `GET /admin/registrations/count` and `GET /admin/registrations?page=&limit=`, listing
Client ID + `expiresAt` for every live CONFIRMED Registration. `recordsByClientId` is a plain
`ConcurrentHashMap` (ADR-0016) with no defined iteration order, and at up to 1 million entries a
full sort or snapshot per page request is real, avoidable cost.

We chose to page directly over the map's own iteration order with plain `page`/`limit` params,
with no sort and no snapshot. This means pagination is best-effort: under concurrent
Register/Renew/Cancel/Expiration, a page can skip or duplicate Client IDs relative to a
strictly-ordered listing, and results aren't reproducible across calls. This is acceptable
because the endpoint is an unauthenticated operational/debugging aid, not a source of truth
consumed by other logic — nothing in the system depends on this listing being exhaustive or
stable. We deliberately rejected sorting by Client ID or by `expiresAt` (even via a bounded,
single-pass top-K selection that would have avoided a full sort) to keep this feature from
touching or adding to `InMemoryRegistrationStore`'s core data structures.
