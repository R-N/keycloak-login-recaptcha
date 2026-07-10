# keycloak-login-recaptcha

By default Keycloak supports Google reCAPTCHA only on the **registration** form, not on login. This is a small Keycloak authenticator plugin (SPI provider) that adds reCAPTCHA to the **login** form.

Supports reCAPTCHA **v2** (checkbox) and **v3** (invisible), with optional `recaptcha.net` domain instead of `google.com`.

> Tested against **Keycloak 24.0.5**.

## How it works

A custom `Authenticator` (`RecaptchaUsernamePasswordForm`) extends Keycloak's built-in `UsernamePasswordForm`. On render it injects the reCAPTCHA widget and script into the login page; on submit it verifies the `g-recaptcha-response` token server-side against Google before delegating to the normal username/password validation.

The plugin ships three things that work together:

| File | Role |
|------|------|
| `RecaptchaUsernamePasswordForm` / `...Factory` | The authenticator + its factory and admin config fields |
| `login.ftl` | Overridden `base` login theme — renders the captcha widget when the authenticator requests it (`<#if recaptchaRequired??>`) |
| `playground-realm.json` | Demo realm; also sets `X-Frame-Options` / `Content-Security-Policy` headers so the reCAPTCHA iframe is allowed to load |

## Build

```bash
mvn clean install
```

Produces `target/recaptcha-login.jar`.

## Run the demo

```bash
docker-compose up
```

`docker-compose.yml` runs the Quarkus Keycloak distro (`quay.io/keycloak/keycloak:24.0.5`, `start-dev --import-realm`). It mounts the built jar into `/opt/keycloak/providers/` (auto-detected), the overridden `login.ftl` into the `base` theme, and imports `playground-realm.json` from `/opt/keycloak/data/import/`.

Then:

1. Open `localhost:8080`, log in with `admin` / `admin`.
2. Select the **Playground** realm.
3. **Authentication → Flows**, pick the **Browser With Recaptcha** flow.
4. On the **Recaptcha Username Password Form** row: **Actions → Config**, and enter the `sitekey` and `secret` from https://www.google.com/recaptcha/admin.
5. **Authentication → Bindings**, set **Browser Flow** to **Browser With Recaptcha**.
6. Create an enabled user with credentials (**Users → Add user**).
7. **Sign Out**, then **Sign In** — the login form now shows reCAPTCHA.

## Configuration options

Configured per execution in the admin console (Authentication → Flows → config):

| Field | Key | Description |
|-------|-----|-------------|
| Recaptcha Site Key | `site.key` | Google reCAPTCHA site key |
| Recaptcha Secret | `secret` | Google reCAPTCHA secret |
| Recaptcha Action | `action` | Action name (reCAPTCHA v3) |
| use recaptcha.net | `useRecaptchaNet` | Use `recaptcha.net` domain instead of `google.com` |
| use recaptcha v3 | `recaptcha.v3` | Enable invisible / v3 mode |
| Recaptcha v3 score threshold | `score.threshold` | Minimum v3 score (0.0–1.0) to pass. Default `0.5`. Ignored for v2 |
| Recaptcha v2 widget size | `size` | v2 checkbox size: `normal` or `compact`. Default `normal`. Ignored for v3 |

For **v3**, verification checks the returned `success` **and** that `score >= score.threshold` (and that the action matches, when set). For **v2** it is a pass/fail on `success`.

## Notes

- Keycloak dependencies are `provided` scope — supplied by the server at runtime, not bundled in the jar.
- The captcha block in `login.ftl` is gated on `recaptchaRequired`, so the stock login flow (without this authenticator) is unaffected.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
