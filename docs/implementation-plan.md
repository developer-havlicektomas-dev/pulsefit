# PulseFit — Implementation Plan

Each step is small enough to finish and verify on its own. **Test** says what proves the step is done: an automated test where possible, otherwise a manual check with an exact expected result. A step is not complete until its test passes.

Specs: [android-spec.md](android-spec.md), [backend-spec.md](backend-spec.md). Designs come from [design-prompt.md](design-prompt.md) and are needed before Phase 3 UI work; Phases 0–2 do not depend on them.

Order: the backend (Phase 1) and the Android foundation + recording (Phases 2, 4, 5) are independent and can run in parallel. Sync (Phase 8) needs both. Wear (Phase 9) needs Phases 4–5.

```
0 Setup ─┬─ 1 Backend ───────────────────────────────┐
         └─ 2 Android foundation ─ 3 Auth ───────────┤
                    └─ 4 Recording engine ─ 5 Recording UI ─ 6 Home ─ 7 Detail ─ 8 Sync ─ 10 Release
                                                └──────────── 9 Wear OS ─────────────┘
```

## Projects and naming

The product is **PulseFit**. Code lives in two independent Gradle builds in one Git repository:

| Folder | Contents | Starting point |
|---|---|---|
| `pulsefit-android/` | Phone app `:app` and Wear OS app `:wear` (android-spec) | Android Studio template: single `:app` module, Compose, AGP 9.5, `rootProject.name = "pulsefit"` |
| `pulsefit-api/` | Spring Boot API (backend-spec) | Spring Initializr: Boot 4.1, Kotlin 2.3, Java 21, only `spring-boot-starter`. Currently nested one level too deep at `pulsefit-api/pulsefit/` |

| Identifier | Value |
|---|---|
| Display name | PulseFit (phone, watch, launcher, notifications) |
| Application id (phone **and** watch) | `dev.havlicektomas.pulsefit` |
| Android module namespaces | `dev.havlicektomas.pulsefit.<module path>`, e.g. `…pulsefit.core.domain`, `…pulsefit.feature.tracking.presentation`, `…pulsefit.wear` |
| Backend base package | `dev.havlicektomas.pulsefit` with `auth`, `activity`, `account`, `common` |
| Auth deep link | `pulsefit://auth-callback` |
| Data Layer capability | `pulsefit_phone` |
| Supabase projects | `pulsefit-dev`, `pulsefit-prod` |
| Database role | `pulsefit_api` |
| Docker image | `pulsefit-api` |

## Phase 0 — Setup

**0.1 Repository.** `git init` at the repository root containing `docs/`, `pulsefit-android/` and `pulsefit-api/`. Flatten `pulsefit-api/pulsefit/*` into `pulsefit-api/` (remove its `build/`, `.gradle/`, `.idea/` and `pulsefit.iml`). Root `.gitignore` for `.DS_Store`; each project keeps its own `.gitignore` (`build/`, `.gradle/`, `local.properties`, `.idea/` except shared run configs). Secrets stay out of git (`local.properties`, `.env`); commit a `.env.example` for the API and document the required `local.properties` keys.
*Test:* fresh clone contains no keys; `./gradlew build` succeeds in both `pulsefit-android/` and `pulsefit-api/`; `git status` is clean afterwards.

**0.2 Supabase projects.** Create `pulsefit-dev` and `pulsefit-prod`; enable Email (confirmation on) and Google providers; asymmetric JWT signing keys; redirect URL `pulsefit://auth-callback`.
*Test:* `curl $SUPABASE_URL/auth/v1/.well-known/jwks.json` returns at least one key; a user created in the dashboard can obtain a token via the Auth REST endpoint.

**0.3 Google Cloud.** Project `pulsefit` with Maps SDK for Android enabled; Maps key restricted to package `dev.havlicektomas.pulsefit`; OAuth web client and Android client (debug + release SHA-1).
*Test:* keys visible with the correct restrictions in the console; web client id is set in the Supabase Google provider.

**0.4 CI.** Two jobs, one per project (working directory `pulsefit-api/` and `pulsefit-android/`), each triggered only when its folder or the workflow changes; both run on every push to `main`.
*Test:* a deliberately failing unit test in either project turns its job red; reverting turns it green; a docs-only change runs no build.

## Phase 1 — Backend (`pulsefit-api/`)

**1.1 Skeleton.** Extend the Initializr project (`PulsefitApplication`, package `dev.havlicektomas.pulsefit`): add the starters from backend-spec §2 (web, validation, security + OAuth2 resource server, data JPA, Flyway — in Boot 4 Flyway has its own starter — PostgreSQL driver, Actuator) and the Kotlin `plugin.jpa`; Testcontainers + `spring-security-test` for tests. Convert `application.yaml` to read the env vars from backend-spec §3. Problem-details error handler, Dockerfile producing image `pulsefit-api`, Testcontainers base test replacing `PulsefitApplicationTests`.
*Test:* `./gradlew test` green; `docker run pulsefit-api` then `GET /actuator/health` → `200 {"status":"UP"}`.

**1.2 Schema.** Flyway migration with the `app` schema, tables, indexes, RLS enabled (backend-spec §5).
*Test:* integration test starts Postgres, runs migrations, asserts tables exist and each check constraint rejects a bad row. Against dev Supabase: an anon-key PostgREST request for `activities` returns nothing.

**1.3 JWT security.** Resource-server config (JWKS, issuer, audience), current-user resolver, just-in-time profile creation, `GET /v1/me`.
*Test:* MockMvc: no token 401, expired 401, wrong audience 401, valid 200 with the right `sub`, profile row created once. Manual: real dev token against a local run returns 200.

**1.4 Create activity.** `PUT /v1/activities/{id}` with validation, batch point insert, idempotency.
*Test:* integration tests for every row of the "Create" line in backend-spec §10, including a 10 000-point payload under 2 s and a gzip-encoded request.

**1.5 Read endpoints.** `GET /v1/activities/{id}`, `GET /v1/activities/{id}/points`.
*Test:* returns what was stored, points in `seq` order; another user's id → 404.

**1.6 Patch and delete.** Title update with version check; soft delete removing points.
*Test:* "Patch" and "Delete" lines of §10.

**1.7 Delta feed.** `GET /v1/activities` with opaque cursor.
*Test:* seed 250 rows, page with `limit=100` → 3 pages, no duplicates or gaps; an update and a delete made after the first sync appear exactly once in the next call; malformed cursor → 400.

**1.8 Account deletion.** `DELETE /v1/me`.
*Test:* integration test asserts rows gone, Auth Admin delete called, old token rejected, retry after simulated admin failure succeeds.

**1.9 Limits and deploy.** Rate limiting, body size limits, structured logs; deploy the image against dev Supabase.
*Test:* 121st request in a minute → 429; log lines contain a request id and no coordinates; a scripted smoke test (create → list → patch → delete) passes against the deployed URL.

## Phase 2 — Android foundation (`pulsefit-android/`)

**2.1 Project and modules.** Turn the template into the module tree from android-spec §2: add `build-logic` convention plugins (included build) and move common config there (compile/target SDK, Java 17 bytecode instead of the template's 11, JUnit 5 instead of JUnit 4); bring the template's outdated catalog versions (lifecycle, activity-compose, espresso, androidx-junit) up to current stable; set phone `minSdk` to 28 per spec (template has 29); `app_name` = "PulseFit"; `PulseFitApplication` starting Koin; move the template `ui/theme` into `:core:presentation:designsystem`; empty `:wear` module with the same `applicationId` `dev.havlicektomas.pulsefit` and `minSdk 30`. Delete the template's example tests.
*Test:* `./gradlew assembleDebug` builds phone and wear APKs, both with application id `dev.havlicektomas.pulsefit`; app launches to a placeholder screen titled "PulseFit"; a JVM test in `:core:domain` runs under JUnit 5.

**2.2 Core domain.** Models, `Result`/`DataError`, speed-category function, haversine distance, default-title generator, track simplification and polyline encoding (preview polylines), formatters (distance, duration, speed, pace; metric/imperial).
*Test:* unit tests: every boundary in the speed table for all four types; known-distance pairs within 0.5 %; simplification keeps segment endpoints and stays within 100 points for a 10 000-point track; polyline encode/decode round-trips against Google's documented example; titles for morning/afternoon/evening/night; formatter output in two locales.

**2.3 Design system.** Theme (light/dark), tokens from the designs, shared components listed in the design prompt, with previews.
*Test:* screenshot tests (or reviewed previews) for each component in both themes; TalkBack reads a label for every icon button.

**2.4 Navigation shell.** Type-safe routes for all screens with placeholders; start destination switches on a fake session flag.
*Test:* Compose navigation test walks Welcome → Home → Detail → back; flipping the flag swaps the start destination.

**2.5 Adaptive foundation.** Material 3 adaptive dependency; window-class helper in `:core:presentation:ui`; `MainActivity` resizable with the `configChanges` from android-spec §11.1 and no orientation lock; Home and Detail placeholders hosted in a `NavigableListDetailPaneScaffold`; a shared Compose test helper that renders a screen at compact, medium and expanded widths and at compact height (`DeviceConfigurationOverride`); Pixel Fold-class foldable and tablet emulators added to the team's device list.
*Test:* Compose UI test at expanded width shows list and placeholder detail together, at compact width only the list; selecting an item and switching width keeps the selection. Manual on the foldable emulator: unfolding does not recreate the activity (a log line in `onCreate` appears once).

## Phase 3 — Authentication

**3.1 Auth data layer.** supabase-kt Auth wrapped in `AuthRepository` (sign up, sign in, sign out, reset, session flow); Ktor client with bearer provider and one refresh-and-retry on 401.
*Test:* unit tests with Ktor `MockEngine`: token attached; 401 → refresh → retry succeeds; second 401 → signed-out event. Error mapping test for each auth error.

**3.2 Email flows.** Welcome, Sign up, Sign in, Forgot password, Check-your-email screens with validation; deep link handling.
*Test:* ViewModel tests (Turbine) for validation and state transitions. Manual on dev: sign up → email link opens the app signed in; wrong password shows the error; reset link lets the user set a new password.

**3.3 Google sign-in.** Credential Manager → ID token → Supabase.
*Test:* manual on a device with a Google account: first tap creates the user (visible in Supabase), second launch is signed in without prompts; cancelling shows no error.

**3.4 Session lifecycle.** Persisted session, sign out, `GET /v1/me` on start.
*Test:* kill and relaunch stays signed in; sign out returns to Welcome and Back does not re-enter Home; backend log shows the authenticated call.

## Phase 4 — Recording engine

**4.1 Database.** Room entities/DAOs for activities and track points, mappers.
*Test:* in-memory Room tests: insert/read round trip, cascade delete, points ordered by `seq`.

**4.2 Location client.** Fused provider wrapper exposing a `Flow`, per-type profiles, batching toggle, stationary back-off, fix filters.
*Test:* unit tests feeding synthetic fixes: inaccurate, stale and teleporting fixes are dropped; stationary input switches the profile and movement switches it back; each type yields the request parameters from the table.

**4.3 Recording manager.** Pure state machine: start, pause, resume, finish; segments; duration excluding pauses; distance; smoothed speed; category times; periodic flush to Room.
*Test:* unit tests with a fake clock and fake location flow: a scripted route gives the expected distance (±1 %), active vs elapsed time, segment count, max speed and category totals; illegal transitions are ignored.

**4.4 Foreground service.** `TrackingService` with notification and actions, permission flow, location-settings resolution.
*Test:* manual with the emulator's route playback: Start shows the notification with ticking values; Pause from the notification stops updates — `adb shell dumpsys location` lists no request for the app; after Finish the service is gone.

**4.5 Crash recovery.** Restore an unfinished session as Paused.
*Test:* instrumented test seeds a RECORDING session and asserts it is restored as PAUSED with its points. Manual: `adb shell am kill` mid-recording, reopen → paused session with the track so far.

## Phase 5 — Recording UI

**5.1 Start flow.** FAB, type picker sheet, permission rationale, navigation to Recording.
*Test:* ViewModel test: last used type is preselected and persisted. Compose UI test: the picker is a bottom sheet at compact width and a dialog at medium width. Manual: denying permission shows the rationale and does not start a session.

**5.2 Recording screen.** Map with follow camera and speed-coloured polylines, metrics panel (bottom, or side panel on expanded width / compact height), pause/resume/finish, all states.
*Test:* Compose UI test with a fake `RecordingManager`: each state renders the right controls and labels at compact width, expanded width and compact height, with no control overlapping another; category chip changes with speed. Manual with route playback: line follows the route, colours change with playback speed, a pause leaves a gap; on the foldable emulator unfolding and folding mid-recording keeps the track continuous and the camera following.

**5.3 Battery behaviour.** Batching when UI hidden; unbatch on return.
*Test:* manual: screen off 10 minutes during playback → continuous track, `dumpsys location` shows the batched request; Battery Historian/energy profiler shows no wake lock held by the app.

**5.4 Summary.** Summary screen with the track map, title edit, save/discard (two columns on wide windows); preview polylines computed on save.
*Test:* instrumented test: after Save the activity is in Room as `PENDING_CREATE` with non-empty preview polylines; Discard leaves no rows; a title typed before rotating or unfolding is still there afterwards. Manual offline with no tiles: Save works at once and the summary shows the track line on a plain background.

## Phase 6 — Home

**6.1 Grouped list.** Paged Room query, day separators with totals, rows with thumbnails, empty and loading states, in-progress bar.
*Test:* unit test of the grouping/label logic across midnight, time zones and year boundaries; Compose UI test with seeded data shows headers "Today", "Yesterday" and a dated one in order.

**6.2 Search.** Debounced title search.
*Test:* DAO test for case-insensitive substring matches; ViewModel test that rapid typing triggers one query.

**6.3 Filters and sort.** Filter sheet (dialog on medium+ width), chips, combined query, result count.
*Test:* DAO tests for each filter alone and three combinations; ViewModel test that removing a chip updates the query; UI test for the "no results → Clear filters" path; filter selections survive a width change while the sheet/dialog is open.

**6.4 Map thumbnails.** `TrackMapImage` (lite-mode map, fitted camera, markers, plain-background fallback) used in rows; on-device thumbnail cache behind the `LOCAL_THUMBNAIL_CACHE` flag (android-spec §8.3).
*Test:* unit test for bounds/camera fitting from preview polylines; Compose UI test: fallback track is drawn when the map does not load within 5 s. Manual: scrolling 200 activities stays smooth; second visit shows cached thumbnails offline; with the flag off no files appear in `cacheDir/thumbnails`; a network capture during browsing shows no image uploads.

## Phase 7 — Detail

**7.1 Detail screen.** Lite-mode map header, all data fields, speed breakdown, full-screen map.
*Test:* ViewModel test maps an activity to the UI model (pace only for walking/running, hidden elevation when null); manual: full-screen map shows the same route as the header; an activity without local points shows the preview track in the header and loads full points when the map is opened.

**7.2 Edit and delete.** Title dialog with validation; delete with confirmation.
*Test:* ViewModel tests: valid title → `PENDING_UPDATE`; blank or > 80 chars rejected; deleting a never-synced activity removes rows and cached thumbnail at once; deleting a synced one marks `PENDING_DELETE` and hides it from Home.

**7.3 Two-pane Home and Detail.** Real Home list and Detail in the list-detail scaffold from 2.5: selected-row highlight, "Select an activity" placeholder, Detail without a back arrow when both panes show, height-capped map header and 3/4-column stats grid, max content width for single-pane screens (auth, settings, lone list).
*Test:* Compose UI tests at expanded width: tapping rows swaps the detail pane without navigating; deleting the shown activity returns to the placeholder; Back from detail at compact width returns to the list at the same scroll position. Manual: the two foldable rows of android-spec §12.

## Phase 8 — Sync

**8.1 API client.** Ktor data source for all endpoints, DTOs, mappers, gzip.
*Test:* `MockEngine` tests for request shape and every documented status → `DataError` mapping; contract check against the deployed dev backend with a test account.

**8.2 Push.** `SyncWorker` steps 1–3 (creates, updates with 409 handling, deletes).
*Test:* worker tests with a fake API: each pending state ends `SYNCED`/removed; a failure midway leaves remaining items pending and they complete on the next run; 409 retried once with the new version.

**8.3 Pull.** Delta feed with cursor, tombstones, protection of local pending rows, on-demand points.
*Test:* worker tests: multi-page pull stores the last cursor; tombstone removes a local row; a locally edited row is not overwritten. Manual: install on a second device, sign in → history appears with map thumbnails drawn from preview polylines; opening a detail map fetches points.

**8.4 Triggers and UI.** Enqueue points, pull-to-refresh, row indicators, offline banner, sign-out warning, Settings "Sync now".
*Test:* manual airplane-mode scenario from android-spec §12: record, rename, delete offline → after reconnecting the backend database matches without opening the app again (within the WorkManager run).

**8.5 Account deletion.** Settings flow calling `DELETE /v1/me` and wiping local data.
*Test:* manual on dev: after deletion the user and rows are gone and the app is on Welcome; signing up again with the same email starts empty.

## Phase 9 — Wear OS

**9.1 Wear shell.** Compose for Wear OS app, theme, type picker, Ready screen, permission flow.
*Test:* runs on the Wear emulator (round, small and large); screenshot tests for both sizes.

**9.2 Watch recording.** Health Services exercise session behind the shared recording interface, Ongoing Activity, ambient mode, local Room store.
*Test:* unit tests reuse the Phase 4.3 scripts against the watch implementation with a fake exercise client. Manual with Health Services synthetic data: metrics tick, pause stops them, finishing stores an activity; no exercise is active afterwards.

**9.3 Recording pager.** Metrics, map and controls pages.
*Test:* Compose UI test with fake state for recording/paused; manual: route draws on the watch map and rotary input zooms.

**9.4 Data Layer protocol.** `:core:connectivity` paths (prefix `/pulsefit/`), DTOs and clients on both sides; phone advertises capability `pulsefit_phone`; session state item.
*Test:* serialisation round-trip unit tests for every message; manual with paired emulators: starting on one side makes the other show the in-progress state within 2 s and blocks a second start.

**9.5 Mirror mode.** Phone-owned session shown and controlled from the watch (ticks, incremental points, commands).
*Test:* manual: start on phone → watch shows live values and the route; Pause on the watch pauses the phone within 2 s; with Bluetooth off the watch shows "Phone not reachable".

**9.6 Transfer to phone.** Channel transfer with acknowledgement and retry; phone stores (computing preview polylines) and syncs.
*Test:* instrumented test on the phone side: receiving the same transfer twice stores one activity. Manual: record on the watch with the phone off, turn it on → activity appears on the phone, is acknowledged (watch store empty), syncs, and shows a map thumbnail on Home.

## Phase 10 — Hardening and release

**10.1 End-to-end pass.** Run every row of android-spec §12 and backend-spec §10 against the dev environment.
*Test:* checklist signed off with no open failures.

**10.2 Quality gates.** R8/minify release build, baseline profile, accessibility scan, large-font and dark-theme review, strict-mode clean, large-screen review against Google's large-screen app quality guidelines (tier 2, "optimised").
*Test:* release build completes the prototype flow from the design prompt on a phone and on an unfolded foldable; Accessibility Scanner reports no errors on main screens; split-screen and rotation row of android-spec §12 passes on the foldable and tablet emulators.

**10.3 Field test.** One real outing per activity type on a physical phone, one on the watch, and one on a physical foldable (unfolding and folding at least three times during the outing).
*Test:* recorded distance within 3 % of a reference (known route length); the foldable track has no gaps at fold/unfold moments; battery drain for a 60-minute cycling session with screen off recorded and judged acceptable (target ≤ 8 % on a recent phone).

**10.4 Store readiness.** `pulsefit-prod` Supabase and backend, Play listing "PulseFit" (name checked for trademark conflicts), release signing and SHA-1s registered, privacy policy, Data Safety form, foreground-service declaration, account-deletion link.
*Test:* internal-testing build installed from Play signs in with Google and email, records, and syncs against prod.
