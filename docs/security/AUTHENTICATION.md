# Authentication implementation rules

**Identity owner:** Keycloak, which brokers local identity and Google OIDC. **Business identity owner:** Accounts & Partners, referenced by verified Keycloak `sub`. Passwords remain inside Keycloak, not in business service tables.

## Login architecture

React/Vite is a **public OAuth/OIDC client** using **Authorization Code + PKCE (S256)** with `state`/`nonce`, registered redirect URIs and restricted origins. Login with Google goes **React → Keycloak → Google → Keycloak → React**; React exchanges a *Keycloak authorization code* using PKCE and sends the **Keycloak access JWT** to Devarom APIs. Do not accept Google's ID/access token directly for domain API access. No OAuth client secret in a browser bundle.

Tokens: short-lived access tokens in **browser memory**, never localStorage, logs, query params or screenshot. Document reload/logout/expiry and refresh/reauth decisions explicitly. Use CSP, dependency hygiene, safe redirect validation and no token leakage in error traces.

## Authorization (not just authentication)

Gateway does initial verification and rate/edge policy; each core service independently checks JWT issuer/signature/audience/expiry, roles, status, ownership and tenant. Platform's role model should include CUSTOMER, MERCHANT, COURIER, DISPATCHER, ADMIN but **new users cannot assign privileged roles to themselves**. Merchant approval is a separate business state, not merely a claim selected during registration. A courier can only update assigned parcels; merchant keys are scoped to a credential-bound approved merchant.

## November 1 demonstrations

- Local sign-in/out, correct current-user profile, Google sign-in if configured with real OAuth consent app.
- Safe denial for unauthenticated/expired/invalid token, wrong role and cross-owner request.
- Controlled signup/role bootstrap; no fake passing tests.
- Never commit real secrets, user credentials, Google OAuth key, realm exported secret fields or private tokens.

More detailed actor/resource rights: [authorization matrix](AUTHORIZATION_MATRIX.md). Sequence diagram [13](../diagrams/rendered/13_google_oidc_sequence.svg).
