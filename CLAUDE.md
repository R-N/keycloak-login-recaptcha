# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Keycloak server-side authenticator plugin (SPI provider) that adds Google reCAPTCHA to the **login** form. Keycloak natively supports reCAPTCHA only on registration, not login. Packaged as a JAR dropped into Keycloak's deployments dir.

Tested against Keycloak **24.0.5**.

## Build & run

```bash
mvn clean install          # produces target/recaptcha-login.jar
docker-compose up          # starts Keycloak with the jar + theme + realm mounted
```

- No test suite exists (`mvn test` is a no-op).
- `docker-compose.yml` runs the Quarkus distro `quay.io/keycloak/keycloak:24.0.5` with `start-dev --import-realm`. Mounts: built jar (→ `/opt/keycloak/providers/`, auto-detected), overridden `login.ftl` (→ `themes/base/login/login.ftl`), `playground-realm.json` (→ `/opt/keycloak/data/import/`).
- Admin login after `up`: `localhost:8080`, user `admin` / pass `admin`, realm `Playground`.
- Java **17** (KC 24 runtime requires it). Keycloak deps are `provided` scope (supplied by the server at runtime, not bundled).

## Architecture

Three pieces must stay in sync — the Java authenticator, the SPI registration, and the FreeMarker template.

1. **`RecaptchaUsernamePasswordForm`** (`src/main/java/.../authenticator/`) — extends Keycloak's `UsernamePasswordForm`. Adds a captcha layer around the stock username/password flow:
   - `authenticate` → calls `applyCaptcha` to push captcha attributes + the reCAPTCHA `api.js` script into the form, before delegating to `super.authenticate`.
   - `action` → reads `g-recaptcha-response` from POST data, verifies it server-side via `POST /recaptcha/api/siteverify` (`validateRecaptcha`), and only then delegates to `super.validateForm`. Verification failure forces a re-challenge with `Messages.RECAPTCHA_FAILED`. For v3, `validateRecaptcha` also enforces `score >= score.threshold` and (if set) an action match — `success` alone is not enough.
   - Config keys (constants in this class): `site.key` (SITE_KEY), `secret` (SITE_SECRET), `action` (ACTION), `useRecaptchaNet` (google.com vs recaptcha.net domain), `recaptcha.v3` (INVISIBLE — v3/invisible mode), `score.threshold` (SCORE_THRESHOLD, default 0.5, v3 only), `size` (SIZE, v2 widget size normal/compact).
   - **Stateless by contract**: the factory shares one `SINGLETON` instance across all requests, so this class must hold no per-request instance fields — everything is read from the context/form per call.

2. **`RecaptchaUsernamePasswordFormFactory`** — `AuthenticatorFactory`, `PROVIDER_ID = "recaptcha-u-p-form"`, display name "Recaptcha Username Password Form". Defines the admin-console config fields (the `CONFIG_PROPERTIES` static block — each maps to a constant above). Requirement choices are REQUIRED / DISABLED only. Registered via `src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`.

3. **`login.ftl`** (repo root) — overrides Keycloak's base login theme. Renders captcha only when the Java side sets `recaptchaRequired`. Branches on `recaptchaVisible`: v2 renders the `g-recaptcha` checkbox (`recaptchaSize`); v3 renders a hidden `g-recaptcha-response` field plus JS that calls `grecaptcha.execute(siteKey, {action})` on submit and injects the token. Stock `UsernamePasswordForm` never sets `recaptchaRequired`, so the block stays hidden for non-captcha flows.

### Adding/changing a config field

Touch all three: add a `String` constant + read it in `applyCaptcha`/`action` (Java class), add a matching `ProviderConfigProperty` in the factory's static block, and surface it in `login.ftl` if it affects rendering.

## Admin setup (to actually exercise it)

Build → `docker-compose up` → in admin console: Authentication → Flows → duplicate/select **Browser With Recaptcha** → on the *Recaptcha Username Password Form* row, Actions → Config → enter `sitekey`/`secret` from https://www.google.com/recaptcha/admin. Then Bindings tab → set browser flow to that flow.

Note: `playground-realm.json` sets `Content-Security-Policy: frame-src ... https://www.google.com` to allow the reCAPTCHA iframe — required for the v2 widget to load. (`X-Frame-Options` is `SAMEORIGIN`; the old `ALLOW-FROM` value was deprecated and ignored by browsers — CSP `frame-src` is what actually grants the iframe.)
