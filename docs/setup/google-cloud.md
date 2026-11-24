# Google Cloud setup (step 0.3)

Everything happens in the [Google Cloud console](https://console.cloud.google.com/) under one project. See android-spec (Auth, Maps) for how the app uses these values.

## Signing certificates

The Android OAuth client and the Maps key are both tied to package `dev.havlicektomas.pulsefit` **plus** a signing-certificate SHA-1. The phone and watch apps share the package and are signed with the same key, so one entry covers both.

| Build | SHA-1 | Source |
|---|---|---|
| Debug (this machine) | `6B:D9:02:1D:49:E9:90:52:FF:68:7F:01:C9:E7:CA:D6:F1:BA:3F:3B` | `~/.android/debug.keystore` |
| Release | — | Not created yet, see below |

Each developer machine has its own debug keystore. Print its SHA-1 with:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
```

**Release.** With Play App Signing there are two release certificates: your **upload key** (a keystore you create and keep in your password manager) and Google's **app signing key** (shown in Play Console → Test and release → App integrity after the app is created). Users' installs are signed with the app signing key, so that SHA-1 is the one sign-in and Maps need; add the upload key's SHA-1 too so locally built release APKs work. Neither exists yet, so add both during step 10.4 (store readiness), or earlier if you create the Play Console app sooner.

## 1. Project

- Create project **`pulsefit`** (the project id may get a suffix if `pulsefit` is taken).
- Billing account linked: the Maps SDK requires one even though mobile Maps SDK loads are free.

## 2. Maps SDK for Android

- APIs & Services → Library → **Maps SDK for Android** → Enable.
- APIs & Services → Credentials → Create credentials → **API key**, name it `pulsefit-android-maps`.
  - *Application restrictions:* **Android apps** → add package `dev.havlicektomas.pulsefit` with the debug SHA-1 (and the release SHA-1s later).
  - *API restrictions:* **Restrict key** → only Maps SDK for Android.

## 3. OAuth consent screen (Google Auth Platform)

- Branding: app name **PulseFit**, support email, developer contact email.
- Audience: **External**. While the app is in *Testing*, only listed test users can sign in, so add your own Google account as a test user. Publish to *In production* before release (step 10.4); the basic `openid`, `email`, `profile` scopes need no verification.
- Data access: scopes `openid`, `.../auth/userinfo.email`, `.../auth/userinfo.profile`.

## 4. OAuth clients

Google Auth Platform → Clients → Create client:

1. **Web application**, name `pulsefit-web`. No origins or redirect URIs are needed for native ID-token sign-in. Copy the **client id** and **client secret**.
2. **Android**, name `pulsefit-android-debug`: package `dev.havlicektomas.pulsefit`, debug SHA-1 from the table. Create one more Android client per release SHA-1 later.

The app asks Credential Manager for an ID token for the **web** client id (android-spec, Auth). The Android client is never referenced in code; it only proves to Google that the signed app may request that token.

## 5. Supabase Google provider (pulsefit-dev)

Authentication → Sign In / Providers → **Google**:

- Enable.
- **Client IDs:** the web client id first, then the Android client id(s), comma-separated.
- **Client Secret:** the web client's secret.
- Leave *Skip nonce checks* off.

## 6. Where the values go (never in git)

| Value | `pulsefit-android/local.properties` | Elsewhere |
|---|---|---|
| Maps API key | `MAPS_API_KEY` | CI secret (step 0.4) |
| Web client id | `GOOGLE_WEB_CLIENT_ID` | Supabase Google provider |
| Web client secret | never | Supabase Google provider only |
| Android client id | — | Supabase Google provider |

## Test

- Credentials page shows the Maps key restricted to Android apps (`dev.havlicektomas.pulsefit` + SHA-1) and to Maps SDK for Android only.
- Both OAuth clients are listed; the Android client shows the package and debug SHA-1.
- `curl https://nszqupyiywxmngniiiep.supabase.co/auth/v1/settings -H "apikey: <publishable key>"` reports `"google": true`.
- The full sign-in flow is tested in step 3.3.

## Status

| Item | Done | Notes |
|---|---|---|
| Project | yes | Project id `pulsefit-515716` |
| Maps key (debug SHA-1) | yes | Created 2026-11-24, in `local.properties`. Restrictions to confirm in the console. |
| Consent screen | yes | Testing mode; publish before release (10.4) |
| Web + Android (debug) clients | yes | Web client id in `local.properties`. Android (debug) client `581813318568-glgkb2uup3s1kcecr63c3b2q3uug68cd`. |
| Supabase Google provider (dev) | yes | Web + Android client ids; `/auth/v1/settings` reports `"google": true` (2026-11-24) |
| Release SHA-1s | | Step 10.4 |
