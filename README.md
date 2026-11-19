# PulseFit

Android + Wear OS app for recording outdoor activities (walking, running, inline skating, cycling), with a Spring Boot backend on Supabase.

## Documents

| Document | Contents |
|---|---|
| [docs/android-spec.md](docs/android-spec.md) | Phone app and Wear OS app specification |
| [docs/backend-spec.md](docs/backend-spec.md) | Spring Boot + Supabase specification, REST API contract, database schema |
| [docs/design-prompt.md](docs/design-prompt.md) | Prompt for generating the UI designs |
| [docs/implementation-plan.md](docs/implementation-plan.md) | Phased plan; every step has a test that proves it is done |

## Repository layout

| Folder | Contents |
|---|---|
| `docs/` | Specs, design prompt, implementation plan |
| `pulsefit-android/` | Phone (`:app`) and Wear OS (`:wear`) apps; open this folder in Android Studio |
| `pulsefit-api/` | Spring Boot API; open this folder in IntelliJ IDEA |

Each project is its own Gradle build; run `./gradlew build` inside its folder.

### Local secrets (not in git)

- `pulsefit-api/.env`: copy `pulsefit-api/.env.example` and fill in `SUPABASE_URL`, `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SUPABASE_SERVICE_KEY`.
- `pulsefit-android/local.properties`: `sdk.dir` (written by Android Studio). Later steps add `MAPS_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `GOOGLE_WEB_CLIENT_ID` here.

## System overview

```
┌────────────┐  Data Layer API   ┌──────────────────┐   HTTPS + Supabase JWT   ┌──────────────────┐
│ Wear OS app│◄─────────────────►│  Android app     │─────────────────────────►│ Spring Boot API  │
│ (records or│  state, commands, │  Room = source   │   /v1/activities ...     │ (resource server)│
│  mirrors)  │  finished tracks  │  of truth        │                          └───────┬──────────┘
└────────────┘                   └───────┬──────────┘                                  │ JDBC / REST
                                         │ sign up / sign in / refresh                 ▼
                                         │                                    ┌──────────────────┐
                                         └───────────────────────────────────►│ Supabase         │
                                                  Supabase Auth only          │ Auth · Postgres  │
                                                                              │                  │
                                                                              └──────────────────┘
```

## Key decisions

These are the choices the specs are built on. All six were confirmed on 2026-11-16 (D4 after being revised to keep map imagery off the server). The alternatives are kept for reference.

| # | Decision | Why | Alternative |
|---|---|---|---|
| D1 | **Supabase Auth is the identity provider.** The Android app calls Supabase Auth directly (email/password, Google ID token exchange, refresh). Spring Boot only validates the resulting JWT. | No password handling or token minting in our code; Google sign-in, email confirmation and password reset come for free. | Spring Boot proxies all auth calls so the app knows one host. More code, more attack surface, no functional gain. |
| D2 | **All user data goes through Spring Boot.** The app never talks to Supabase Postgres (PostgREST) directly. | One place for validation, sync logic and authorization; the database is not exposed to clients. | Client uses PostgREST + RLS and Spring Boot shrinks to almost nothing. |
| D3 | **The watch records on its own when the activity is started on the watch**, and mirrors/controls the phone when the activity was started on the phone. | Android does not let a phone app begin location access from the background (a watch message) without the background-location permission, which Play policy makes hard to justify. Standalone recording also works with the phone left at home. | Watch is a pure remote for the phone; requires `ACCESS_BACKGROUND_LOCATION`. |
| D4 | **Store the track, not the picture.** The backend stores only track data (plus a simplified preview of the track). Every map image is drawn on the device with a lite-mode Google Map; a thumbnail bitmap may be cached on the device only. No map imagery is uploaded or stored server-side. | Google's guidance for `GoogleMap.snapshot()` is that map images must not be sent to your servers; the GPS track is the user's own data and can be stored freely. Also removes Storage, upload endpoints and Static Maps billing. | Fetch a Static Maps image at display time and never store it (billed per request, key needs URL signing), or use a non-Google provider whose terms allow stored images. |
| D5 | **Backend is written in Kotlin** (Spring Boot, Gradle). | Same language as the apps. | Java. |
| D6 | **Offline-first with last-write-wins.** Room is the source of truth; a WorkManager job pushes pending changes and pulls a delta. Track points are immutable after creation; only the title can change. | Tiny conflict surface (one mutable field), so no merge logic is needed. | — |

## Open questions

1. **On-device thumbnail cache.** D4 keeps map imagery off the server. The optional on-device thumbnail cache (android-spec §8.3) should still be checked against the current Google Maps Platform terms before release; it sits behind a build flag and the app works without it.
2. **Units.** The spec assumes metric with an imperial toggle in settings.
3. **Heart rate** from the watch is out of scope for v1 (the data model leaves room for it).
4. **Hosting** for the Spring Boot container is unspecified (any container host works; the spec assumes one small instance).
