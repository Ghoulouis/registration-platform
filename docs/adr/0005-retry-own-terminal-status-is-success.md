# Treat a retry's "already in the target state" status as success

Each Register/Renew/Cancel call is a short-lived, one-shot TCP connection (ADR-0001), so a Client that times out waiting for a response can't tell whether the Server never received the request or processed it successfully and only the response was lost. Retrying in that situation risks getting back a status that looks like a failure but is actually evidence the original attempt succeeded:

- Retrying `REGISTER` can get back `ALREADY_REGISTERED` — caused by the Client's own earlier, successful-but-unacknowledged `REGISTER` landing.
- Retrying `CANCEL` can get back `NOT_REGISTERED` — caused by the Client's own earlier, successful-but-unacknowledged `CANCEL` already having deleted the Registration.

We chose to treat both as success, specifically when returned to a retry of that same Client ID's own call (never on a first attempt — there, both statuses are genuine failures). The alternative — counting them as failures — would misclassify the Client's own success as an error purely because of a lost response, undercounting successes in benchmark statistics. The risk this masks a real collision between two independently-generated Client IDs is accepted as negligible: Client IDs are 12-digit random numbers (ADR-0003), so an unrelated collision is roughly 1 in 10¹².

`RENEW` needs no equivalent rule: repeating a successful renewal just returns `SUCCESS` again (extending the TTL further is harmless), so retrying it is naturally idempotent without any special-case interpretation.
