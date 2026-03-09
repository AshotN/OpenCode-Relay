# API Architecture

## Ownership rule

All HTTP and SSE transport code must live under:

- `src/main/kotlin/com/ashotn/opencode/companion/api/**`

Code outside this package must call typed API clients instead of opening connections directly.

## Guardrail

`src/test/kotlin/com/ashotn/opencode/companion/api/TransportGuardrailTest.kt` fails if non-api production code contains:

- `HttpURLConnection`
- `.openConnection(`
- `requestMethod =`

`src/test/kotlin/com/ashotn/opencode/companion/api/EndpointGuardrailTest.kt` fails if any domain endpoint factory file (`*Endpoints.kt`) is placed under `api/transport`.

These guardrails keep transport logic centralized while ensuring endpoint ownership stays with each API client domain.

## Parse error context (`withParseContext`)

When mapping HTTP responses to typed models, parse errors must include request attribution.

Rules:

- Use shared transport parsers (`parseBooleanResponse`, `parseJsonObjectResponse`, `parseJsonArrayResponse`) for mechanical parsing.
- Apply `withParseContext(endpoint)` at API client boundaries when returning `ApiResult<*>` to callers.
- Define endpoints in domain-grouped endpoint factory files next to each API client (for example `api/session/SessionEndpoints.kt`, `api/tui/TuiEndpoints.kt`) and pass the resulting endpoint object to `withParseContext(endpoint)`.
- `ApiEndpoint` context must remain `METHOD /path` format (for example: `GET /session`, `POST /session/{sessionId}/permissions/{permissionId}`).
- Keep parser error messages generic; endpoint attribution is added by `withParseContext(...)`.
- Do not use `withParseContext(...)` to wrap non-parse concerns (`HttpError`, `NetworkError`).
- Methods that do not parse raw HTTP responses (pure in-memory/domain mapping helpers) do not need `withParseContext`.
- Typed HTTP API client methods should return `ApiResult<*>`; map domain-level states (for example "no matching diff file") inside the success value instead of introducing parallel `success` flags.
