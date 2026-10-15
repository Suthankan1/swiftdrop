# React + TypeScript + Vite SPA — planned folder

> **Documentation placeholder only.** There are **no generated sources/configs** in this folder. The responsible team will generate the application using Spring Initializr/Vite or create supporting configs in its own scoped issue.

## Intended scope

Customer/Merchant/Courier/Dispatcher/Admin UI; public redacted tracking, browser auth.

## Ownership/boundary

Generate Vite React+TS with the **Customer pair + Platform review**; use React Router, typed API clients, Keycloak Code+PKCE, in-memory access token, TanStack Query. No backend business logic, OAuth client secret, token localStorage or fake authorization.

## First planned slice

First milestone: local/Google login callback, logout, safe role landing pages; tests for login error and route handling.

Read `AGENTS.md` at repository root, `docs/team/OWNERSHIP.md` where applicable, `docs/architecture/BASELINE.md` and `docs/contracts/README.md` before implementation.
