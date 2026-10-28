# Payment replay observation

Prepare one scenario using the supplied `openapi.yaml` and a target provided by
the operator. Use a fresh, empty payment store and seed 42.

Submit a payment for 4200 minor units in EUR. Repeat the identical request with
the same `Idempotency-Key`, then list the stored payments. Establish that:

- all three operations succeed and their bodies match the response schemas;
- both submissions return the same payment ID;
- exactly one payment was stored, with the requested amount and currency.

Use the observations to decide whether these properties hold. A successful
HTTP status alone does not establish them. Missing body evidence cannot prove
a business property. Keep the scenario fixed while the operator repeats the
experiment against a fresh store; explain the engine's assertion outcomes.

`scenario.yaml` is a reference implementation of this task. The accompanying
OpenAPI document is copied unchanged from the payment-service example. No
additional service operation or test-specific request header is required.
