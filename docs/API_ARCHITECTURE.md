# API Architecture

## Ownership rule

All HTTP and SSE transport code must live under:

- `src/main/kotlin/com/ashotn/opencode/api/**`

Code outside this package must call typed API clients instead of opening connections directly.

## Guardrail

`src/test/kotlin/com/ashotn/opencode/api/TransportGuardrailTest.kt` fails if non-api production code contains:

- `HttpURLConnection`
- `.openConnection(`
- `requestMethod =`

This keeps transport logic centralized and prevents endpoint contract drift from spreading into service layers.
