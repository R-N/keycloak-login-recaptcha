# AGENTS.md

Guidance for AI coding agents working in this repository. (Claude Code users: see `CLAUDE.md` — same content.)

## What this is

Keycloak server-side authenticator plugin (SPI provider) adding Google reCAPTCHA to the **login** form. Keycloak natively supports reCAPTCHA only on registration. Packaged as a JAR dropped into Keycloak's deployments dir. Tested against Keycloak **24.0.5**.

## Build & run

```bash
mvn clean install          # produces target/recaptcha-login.jar
docker-compose up          # starts Keycloak with jar + theme + realm mounted
```

- No test suite exists (`mvn test` is a no-op).
- `docker-compose.yml` runs Quarkus distro `quay.io/keycloak/keycloak:24.0.5` (`start-dev --import-realm`). Mounts: built jar (→ `/opt/keycloak/providers/`, auto-detected), overridden `login.ftl` (→ `themes/base/login/login.ftl`), `playground-realm.json` (→ `/opt/keycloak/data/import/`).
- Admin after `up`: `localhost:8080`, user `admin` / pass `admin`, realm `Playground`.
- Java **17** (KC 24 runtime requires it). Keycloak deps are `provided` scope (supplied by server at runtime, not bundled).

## Architecture

Three pieces must stay in sync — Java authenticator, SPI registration, FreeMarker template.

1. **`RecaptchaUsernamePasswordForm`** (`src/main/java/.../authenticator/`) — extends Keycloak's `UsernamePasswordForm`. Wraps stock username/password flow with a captcha layer:
   - `authenticate` → `applyCaptcha` pushes captcha attributes + reCAPTCHA `api.js` script into the form before delegating to `super.authenticate`. No `createLoginForm` override needed: `context.form()` is memoized per request, so base `authenticate()`'s internal `createLoginForm` hook renders the same form instance `applyCaptcha` already stamped.
   - `action` → reads `g-recaptcha-response` from POST, verifies server-side via `POST /recaptcha/api/siteverify` (`validateRecaptcha`), then delegates to `super.validateForm`. Failure forces re-challenge with `Messages.RECAPTCHA_FAILED`. For v3, verify also enforces `score >= score.threshold` and (if set) action match — not just `success`.
   - Config keys (constants): `site.key`, `secret`, `action`, `useRecaptchaNet` (google.com vs recaptcha.net), `recaptcha.v3` (INVISIBLE), `score.threshold` (v3, default 0.5), `size` (v2 widget, normal/compact).
   - **Stateless by contract**: factory shares one `SINGLETON` across all requests — hold no per-request instance fields, read everything from context/form per call.

2. **`RecaptchaUsernamePasswordFormFactory`** — `AuthenticatorFactory`, `PROVIDER_ID = "recaptcha-u-p-form"`, display name "Recaptcha Username Password Form". Admin-console config fields live in the `CONFIG_PROPERTIES` static block (one per constant). Requirement choices: REQUIRED / DISABLED. Registered via `src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`.

3. **`login.ftl`** (repo root) — overrides Keycloak base login theme. Renders captcha only when Java sets `recaptchaRequired`. Branches on `recaptchaVisible`: v2 = `g-recaptcha` checkbox (`recaptchaSize`); v3 = hidden `g-recaptcha-response` + JS `grecaptcha.execute(siteKey,{action})` on submit. Stock `UsernamePasswordForm` never sets `recaptchaRequired`, so captcha stays hidden for non-captcha flows.

### Adding/changing a config field

Touch all three: add `String` constant + read it in `applyCaptcha`/`action` (Java class), add matching `ProviderConfigProperty` in the factory static block, surface it in `login.ftl` if it affects rendering.

## Conventions

- Match existing code style (tabs in the Java authenticator). Don't reformat untouched lines.
- Target Java **17** (KC 24). Imports use `jakarta.*` (Keycloak 24).
- Don't bundle Keycloak deps — they're `provided`.
- `playground-realm.json` sets CSP `frame-src ... https://www.google.com` to allow the reCAPTCHA iframe — required for the v2 widget. Don't strip it.
- The authenticator is a shared singleton — keep it stateless (see architecture note).
