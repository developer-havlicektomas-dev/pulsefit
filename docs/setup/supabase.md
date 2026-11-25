# Supabase setup (step 0.2)

Do this once for **`pulsefit-dev`** and once for **`pulsefit-prod`**. Everything happens in the [Supabase dashboard](https://supabase.com/dashboard); see [backend-spec §3](../backend-spec.md#3-supabase-setup) for why.

## 1. Create the project

- New project, name `pulsefit-dev` (then `pulsefit-prod`), a region close to the users (both projects in the same region).
- Generate a strong database password and keep it in your password manager. It is the `postgres` password; the API will use its own role `pulsefit_api` (created in step 1.2), not this one.

## 2. Auth → Sign In / Providers

- **Email:** enabled, **Confirm email: on**.
- **Google:** enabled. It needs the **web** OAuth client id and secret, and the Android client id under *Authorized Client IDs*. Those come from step 0.3 (Google Cloud): enable the provider now if you have them, otherwise finish this item as part of 0.3.
- Leave *Skip nonce check* off; the app sends a hashed nonce (android-spec, Auth).

## 3. Auth → URL Configuration

- Add `pulsefit://auth-callback` to **Redirect URLs**.
- Site URL can stay at the default; the app never uses it.

## 4. Project Settings → JWT Keys

- New projects already sign with an asymmetric ECC P-256 (ES256) key; confirm it is the **current** key under *JWT Signing Keys*. On an older project, create a standby ES256 key and rotate to it.
- The API verifies only via JWKS, so the legacy HS256 secret is never used.

## 5. Create a test user and verify

- Auth → Users → **Add user** → *Create new user*, with *Auto Confirm User* on.
- Project Settings → API Keys: copy the **publishable** (or legacy anon) key.

```bash
SUPABASE_URL=https://<ref>.supabase.co SUPABASE_ANON_KEY=<publishable key> TEST_EMAIL=<test user> scripts/verify-supabase.sh
```

It prompts for the test user's password and checks that the JWKS has a key, that the user gets a token, and that the token is asymmetrically signed with a JWKS key, issuer `${SUPABASE_URL}/auth/v1` and audience `authenticated`. Delete the test user from `pulsefit-prod` afterwards.

## 6. Where the values go (never in git)

| Value | `pulsefit-api/.env` | `pulsefit-android/local.properties` |
|---|---|---|
| Project URL | `SUPABASE_URL` | `SUPABASE_URL` |
| Publishable / anon key | — | `SUPABASE_ANON_KEY` |
| Secret / service_role key | `SUPABASE_SERVICE_KEY` | never |
| Session pooler host | `DB_URL` / `DB_USER` (step 1.2, role `pulsefit_api`) | — |

Local development uses `pulsefit-dev`; prod values go only into the deployment's secrets.

## Status

| Project | Ref | Region | Created | Notes |
|---|---|---|---|---|
| `pulsefit-dev` | `nszqupyiywxmngniiiep` | eu-central-1 | 2026-11-23 | JWKS serves one ES256 key. Email with confirmation on, redirect URL set, test user created; `verify-supabase.sh` passed 2026-11-23. Google provider enabled 2026-11-24 with the web and Android client ids (see [google-cloud.md](google-cloud.md)). Complete. |
| `pulsefit-prod` | — | — | — | Not created: the free-plan organisation allows 2 active projects and one is taken by another project. Needed by step 1.9. |
