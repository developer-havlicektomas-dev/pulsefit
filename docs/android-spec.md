# Android & Wear OS Specification

Scope: the phone app (`:app`) and the Wear OS app (`:wear`). The REST contract they consume is in [backend-spec.md](backend-spec.md). Decisions D1–D6 referenced here are in the [README](../README.md).

## 1. Product requirements

| ID | Requirement |
|---|---|
| F1 | Sign up / sign in with email + password or with a Google account. Sign out. Reset password. Delete account. |
| F2 | Start an activity: FAB on Home → choose type (walking, running, inline skating, cycling) → Start. |
| F3 | While recording: record locations, draw the track on Google Maps, show duration, distance, current speed and its category (slow / average / high). |
| F4 | Pause, resume and finish an activity. Location tracking is off unless an activity is actively recording (off while paused). |
| F5 | Location tracking is tuned per activity type to save battery. |
| F6 | Finished activities are stored locally and synced to the backend when a connection is available. |
| F7 | Every activity is shown with a Google Maps image of its track, rendered on the device (D4). |
| F8 | Home lists activities grouped by day of recording, with search and filters. |
| F9 | Activity detail shows the map image of the track and all recorded data; the title can be edited and the activity deleted. |
| F10 | Wear OS app: start, pause, resume, finish, and see the activity on a map. |

Non-goals for v1: social features, routes/navigation, heart rate, auto-pause, import/export (GPX), iOS, foldable posture-specific layouts (tabletop / book), desktop-windowing features (multi-instance, drag and drop), standalone watch sign-in. Large screens and foldables are supported through the adaptive layouts in §11.1.

## 2. Platform and stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3; Compose for Wear OS (Material 3) on the watch |
| SDK levels | Phone: `minSdk 28`, target/compile latest stable. Wear: `minSdk 30` (Wear OS 3+) |
| Architecture | Multi-module, MVI presentation (State / Action / Event), repositories, offline-first |
| DI | Koin |
| Navigation | Type-safe Navigation Compose (serializable route objects) |
| Adaptive layout | Material 3 adaptive (`currentWindowAdaptiveInfo()`, window size classes, `NavigableListDetailPaneScaffold`) |
| Persistence | Room (activities, track points), DataStore (settings, sync cursor) |
| Networking | Ktor client (OkHttp engine), kotlinx.serialization, gzip request bodies |
| Auth | supabase-kt **Auth module only**; Credential Manager + Google ID library for Google sign-in |
| Location | Play Services Fused Location Provider (phone); Health Services `ExerciseClient` (watch) |
| Maps | Maps SDK for Android via `maps-compose` (phone and watch) |
| Background | Foreground service (`location` type) for recording; WorkManager for sync |
| Images | Coil |
| Phone ↔ watch | Wearable Data Layer API (`DataClient`, `MessageClient`, `ChannelClient`, `CapabilityClient`) |
| Tests | JUnit 5, Turbine, AssertK, fakes (no mocking framework), Compose UI tests, Room in-memory tests |

Application id: `dev.havlicektomas.pulsefit` (phone and watch must share it for the Data Layer).

### Modules

```
:app                              phone application, nav host, Koin assembly
:wear                             Wear OS application
:build-logic                      Gradle convention plugins, version catalog
:core:domain                      models, Result/DataError, repository interfaces, pure logic (speed, distance)
:core:data                        Ktor client, auth token provider, safe-call helpers
:core:database                    Room database, DAOs, entities
:core:location                    Fused location wrapper, per-type location profiles, filters
:core:connectivity                Data Layer protocol shared by :app and :wear (paths, DTOs, clients)
:core:presentation:designsystem   theme, colors, typography, shared components
:core:presentation:ui             UiText, formatters (distance, duration, speed, pace), ObserveAsEvents
:feature:auth:{domain,data,presentation}
:feature:tracking:{domain,data,presentation}      recording engine, service, recording + summary screens
:feature:activities:{domain,data,presentation}    home list, filters, detail, sync
:feature:settings:presentation
```

Dependency rule: `presentation → domain ← data`; features never depend on each other; `:core:domain` is pure Kotlin so the recording engine is unit-testable on the JVM.

## 3. Domain model

```kotlin
enum class ActivityType { WALKING, RUNNING, INLINE_SKATING, CYCLING }
enum class SpeedCategory { SLOW, AVERAGE, HIGH }
enum class RecordingSource { PHONE, WATCH }
enum class SyncState { SYNCED, PENDING_CREATE, PENDING_UPDATE, PENDING_DELETE }

data class Activity(
    val id: Uuid,                    // generated on the device
    val type: ActivityType,
    val title: String,               // 1..80 chars; default "Morning Run", "Evening Ride", ...
    val startedAt: Instant,
    val endedAt: Instant,
    val zoneId: String,              // zone at start; defines the "day of recording"
    val activeDurationMs: Long,      // excludes pauses
    val elapsedDurationMs: Long,     // endedAt - startedAt
    val distanceM: Double,
    val avgSpeedMps: Double,         // distance / activeDuration
    val maxSpeedMps: Double,
    val elevationGainM: Double?,
    val timeSlowMs: Long, val timeAverageMs: Long, val timeHighMs: Long,
    val source: RecordingSource,
    val previewPolylines: List<String>, // simplified track: one encoded polyline per segment, ≤ 100 points in total (§8.3)
    val syncState: SyncState,
    val version: Long,               // server version, 0 until first sync
    val updatedAt: Instant,
)

data class TrackPoint(
    val seq: Int,                    // 0-based order within the activity
    val segment: Int,                // increments on every resume; segments are not joined on the map
    val lat: Double, val lng: Double,
    val altitudeM: Double?, val speedMps: Float?, val accuracyM: Float,
    val time: Instant,
)
```

### Speed categories

Category is derived from the smoothed current speed (moving average over the last 5 s) and the activity type. Boundaries are inclusive on the lower side.

| Type | Slow | Average | High |
|---|---|---|---|
| Walking | < 4 km/h | 4 – 6 km/h | > 6 km/h |
| Running | < 8 km/h | 8 – 12 km/h | > 12 km/h |
| Inline skating | < 10 km/h | 10 – 18 km/h | > 18 km/h |
| Cycling | < 15 km/h | 15 – 25 km/h | > 25 km/h |

The same function colours track segments on the map and feeds the `time*Ms` totals. It lives in `:core:domain` and is shared with `:wear`.

## 4. Recording

### 4.1 State machine

```
Idle ──start──► Recording ◄──resume── Paused
                    │  └───pause────────►│
                    └──────finish────────┴──► Finished ──save──► stored (PENDING_CREATE)
                                                   └────discard──► nothing stored
```

- Only one session can exist at a time across phone and watch (see §9).
- Finishing with distance < 20 m or active time < 10 s asks "Discard this activity?" instead of silently saving.
- The session is owned by `TrackingService`, not by a ViewModel. UI observes a `StateFlow<RecordingState>` from a singleton `RecordingManager`.

### 4.2 Foreground service

- `TrackingService`, `foregroundServiceType="location"`, started from the Start button (app is in the foreground, so only while-in-use location permission is needed).
- Ongoing notification: type icon, live duration and distance, actions Pause/Resume and Finish; tapping opens the recording screen.
- Location updates are requested on Start/Resume and **removed on Pause and Finish**. When idle, the app holds no location request and the service is not running.
- Crash/process-death safety: the in-progress session and its points are written to Room as they arrive (flushed every 10 points or 10 s). On restart the service restores the session in the Paused state and the notification asks the user to resume or finish.

### 4.3 Permissions

| Permission | When requested |
|---|---|
| `ACCESS_FINE_LOCATION` (+ coarse) | On first Start, after a rationale screen. If only approximate is granted, explain that precise is required and offer Settings. |
| `POST_NOTIFICATIONS` (API 33+) | On first Start. Denial does not block recording. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Manifest only |

`ACCESS_BACKGROUND_LOCATION` is never requested. If location services are disabled, show the system resolution dialog (`SettingsClient`).

### 4.4 Location profiles and battery

| Type | Interval | Fastest | Min distance | Batching when UI hidden |
|---|---|---|---|---|
| Walking | 5 s | 3 s | 5 m | up to 30 s |
| Running | 3 s | 2 s | 5 m | up to 15 s |
| Inline skating | 2 s | 1 s | 5 m | up to 10 s |
| Cycling | 2 s | 1 s | 10 m | up to 10 s |

Rules:
1. Priority is high accuracy (GPS is required for a usable track); savings come from interval, minimum distance and batching, not from lowering accuracy.
2. **Batching:** when neither the recording screen nor a mirroring watch is visible, the request is replaced with one using `setMaxUpdateDelayMillis` from the table, so fixes are delivered in batches and the CPU can sleep between them. When the UI returns, the unbatched request is restored.
3. **Stationary back-off:** if total displacement over 60 s is under 10 m, switch to a 10 s interval until a fix shows movement again.
4. No wake locks, no sensors other than location, no network calls during recording (sync runs after finish).
5. **Filters** before a fix is accepted: accuracy ≤ 25 m; implied speed from the previous point ≤ 3× the type's "high" boundary; fixes older than 10 s are dropped. The first accepted fix of each segment needs accuracy ≤ 15 m ("Acquiring GPS…" is shown until then, max 30 s, after which 25 m is accepted).
6. Distance is the sum of haversine distances between consecutive accepted points within a segment. Speed uses `Location.speed` when present, otherwise distance/time.

### 4.5 Recording screen

- Full-screen Google Map: camera follows the user (re-centre button appears after a manual pan), track drawn as polylines coloured by speed category, start marker, current-position marker.
- Bottom panel: duration (large), distance, current speed with a category chip (Slow / Average / High, colour-coded), average speed.
- Controls: Pause ↔ Resume; Finish (long-press or confirm dialog to avoid accidental taps). Back navigation leaves recording running and returns to Home, where a "recording in progress" bar replaces the FAB.
- States: acquiring GPS, recording, paused, GPS signal lost (banner, timer keeps running), permission revoked (session pauses, banner with fix action).
- On wide or landscape windows the panel moves to the side (§11.1).

### 4.6 Finish and summary screen

Shows the fitted track map (the shared `TrackMapImage` from §8.3), editable title, and stats; actions Save and Discard.

On Save the app computes `previewPolylines` from the recorded points and stores the activity as `PENDING_CREATE`. Saving never depends on the map having loaded.

## 5. Home screen

- Top bar: search field, filter button with active-filter badge, avatar → Settings.
- List grouped by local day of `startedAt` in the activity's `zoneId`. Sticky headers: "Today", "Yesterday", otherwise "Mon, 12 Oct" (year added when not the current year), each with the day's total distance and duration.
- Row: map thumbnail (§8.3), type icon, title, start time, distance, duration, average speed, small "not synced" indicator when `syncState != SYNCED`.
- **Search:** case-insensitive substring match on title, debounced 300 ms.
- **Filters** (bottom sheet, combinable, shown as removable chips under the top bar): activity type (multi-select), date range, distance range, duration range, average-speed category, source (phone/watch). **Sort:** newest (default), oldest, longest distance, longest duration. Grouping by day is kept for date sorts and dropped for the other two.
- Search and filters are applied in the Room query; the list is paged (Paging 3) with day separators inserted in the pager.
- FAB "Start" → bottom sheet with the four types (last used preselected) and a Start button. Replaced by the in-progress bar while a session exists (on phone or watch).
- Pull to refresh triggers a sync. States: loading, empty ("Record your first activity"), no results for filters (with "Clear filters"), offline banner when pending changes exist.

- On expanded-width windows Home and Detail are shown side by side (§11.1); the FAB and the in-progress bar stay in the list pane.

## 6. Activity detail

- Header: `TrackMapImage` (§8.3), a non-interactive lite-mode map of the track, 16:9. Tap opens an interactive full-screen map with the speed-coloured track; points are loaded from Room or fetched from the backend if this device does not have them.
- Data: title, type, date, start and end time, active duration, elapsed duration, distance, average speed, max speed, average pace (walking/running only), elevation gain (if available), time and percentage in slow/average/high as a segmented bar, recorded on phone/watch, sync status.
- Edit title: dialog, 1–80 characters, trimmed; saves locally, marks `PENDING_UPDATE`.
- Delete: confirmation dialog; row is hidden immediately, marked `PENDING_DELETE`, removed locally (with points and cached thumbnail) once the server confirms. An activity that was never synced is removed at once.

## 7. Authentication

- Screens: Welcome (Continue with Google, Sign up with email, Sign in), Sign up (email, password, confirm; inline validation: valid email, password ≥ 8 chars with a letter and a digit), Sign in, Forgot password, "Check your email" (confirmation pending).
- Email/password: supabase-kt `signUpWith(Email)` / `signInWith(Email)`. Email confirmation is on; the confirmation and password-reset links deep-link back into the app (`pulsefit://auth-callback`).
- Google: Credential Manager `GetGoogleIdOption` with the **web** OAuth client id and a hashed nonce → supabase-kt `signInWith(IDToken)`.
- Session: supabase-kt stores and refreshes tokens (encrypted storage). The Ktor client for our API gets the access token from it per request; on `401` it forces one refresh and retries once, then signs the user out.
- Start destination is decided by session state: Welcome when signed out, Home when signed in.
- **Sign out:** if unsynced activities exist, warn that they will be lost and offer "Sync now". Signing out clears Room, cached thumbnails and the sync cursor (local data is per account).
- **Delete account** (Settings): confirm by typing "DELETE" → `DELETE /v1/me` → local wipe → Welcome.
- Errors map to `UiText`: invalid credentials, email already registered, email not confirmed, no connection, Google sign-in cancelled (silent).

## 8. Storage and sync

### 8.1 Room

- `activities` (fields of `Activity`, plus `status` RECORDING/PAUSED/FINISHED for crash recovery, and `deleted` flag); indices on `startedAt`, `type`, `syncState`.
- `track_points` (`activityId` FK cascade, `seq`, …); primary key `(activityId, seq)`.
- All list/detail screens read from Room `Flow`s only. Network results are written to Room, never shown directly.

### 8.2 Sync worker

Unique `SyncWorker` (WorkManager, constraint `NetworkType.CONNECTED`, exponential backoff from 30 s). Enqueued: after save/edit/delete, on app start, on pull-to-refresh, on receipt of a watch activity, and periodically every 6 h.

Each run, in order:
1. **Push creates:** for each `PENDING_CREATE`, `PUT /v1/activities/{id}` with metadata and all points (gzip). On success store `version`, set `SYNCED`.
2. **Push updates:** `PATCH /v1/activities/{id}` with `title` and `version`. On `409`, take the server version from the response and retry once (local title wins).
3. **Push deletes:** `DELETE /v1/activities/{id}`; `204` and `404` both count as done → remove locally.
4. **Pull:** `GET /v1/activities?cursor=…` until `hasMore` is false; upsert metadata, apply tombstones, save the cursor in DataStore. Rows with a local pending change are not overwritten. Track points are not pulled here (fetched on demand by the detail map).
Failures: `4xx` other than `401/409` marks the item failed and surfaces "Couldn't sync" on the row with a retry; `5xx`/IO errors retry the worker. All writes are idempotent, so a retry after a lost response is safe.

### 8.3 Map images (D4)

Map imagery is never uploaded and never stored on the backend. The backend stores the track; every map picture is drawn on the device from track data.

- **Preview polylines.** At save time the track is simplified per segment (Douglas–Peucker, in `:core:domain`) to at most 100 points in total and encoded in Google's encoded-polyline format. `previewPolylines` is part of the activity metadata, so it syncs with the delta feed and any device can draw a thumbnail without downloading the full track.
- **`TrackMapImage` composable.** A lite-mode `GoogleMap` (non-interactive, no controls), camera fitted to the track bounds with padding, start and end markers, dark map style in dark theme. It draws speed-coloured polylines from the full points when they are in Room, otherwise the preview polylines in the primary colour. Used by the summary screen, the detail header and Home rows.
- **On-device thumbnail cache.** After a row's lite map has loaded, `GoogleMap.snapshot()` is scaled to 400×225 and written to `cacheDir/thumbnails/{activityId}-{light|dark}.webp`. Rows show the cached bitmap (Coil) when present and the lite map otherwise. The cache is regenerable, never leaves the device, and is cleared on sign-out and on delete. It sits behind the build flag `LOCAL_THUMBNAIL_CACHE`; with the flag off, rows always use lite maps.
- **No map available.** If the map has not loaded within 5 s (offline with no cached tiles), the track line is drawn on a plain surface with Compose `Canvas` from the same polylines, and replaced by the map when it loads.
- **Watch recordings** need nothing special: the phone computes `previewPolylines` on receipt and renders like any other activity.

## 9. Wear OS app

The watch has no account and no backend access. It needs the phone app installed (non-standalone). Everything it records reaches the backend through the phone.

### 9.1 Modes (D3)

| Mode | When | Who records | Watch role |
|---|---|---|---|
| **Watch recording** | Activity started on the watch | Watch (Health Services `ExerciseClient`: location, speed, distance; exercise type mapped from `ActivityType`, falling back to a generic type plus manual distance if a type is unsupported on the device) | Full recorder; works without the phone nearby |
| **Mirror** | Activity started on the phone | Phone | Shows live state; sends pause / resume / finish commands |

If a session already exists on the other device, Start is not offered; the watch opens straight into Mirror mode, and the phone shows a "Recording on watch" bar instead of the FAB.

### 9.2 Screens

1. **Type picker:** scrolling list of the four types; tapping one goes to Ready.
2. **Ready:** GPS status, large Start button. Location permission requested here on first use.
3. **Recording** (horizontal pager, same in both modes):
   - *Metrics:* duration, distance, current speed with category colour.
   - *Map:* Google Map with the track and current position, auto-follow; rotary input zooms.
   - *Controls:* Pause/Resume, Finish (confirm).
4. **Summary:** distance, duration, average speed; "Saved – will sync with phone" (or "Synced").

Also: an Ongoing Activity so the session shows on the watch face and in recents; ambient mode shows duration and distance only, updated once a minute; map page is not rendered in ambient.

### 9.3 Data Layer protocol (`:core:connectivity`)

| Path | Transport | Direction | Purpose |
|---|---|---|---|
| `/session/state` | `DataItem` | owner → other | `{sessionId, owner, type, status, startedAt, activeBaseMs, statusChangedAt}`; written only on state changes. Timers run locally from these values. Deleted when the session ends. |
| `/session/tick` | Message | owner → other | `{distanceM, speedMps, category}` every 3 s, only while the other side has the recording UI visible |
| `/session/points` | Message | phone → watch | incremental batches of new points for the mirror map |
| `/session/track-request` | Message | watch → phone | asks for the full track so far when the map page opens |
| `/session/cmd` | Message | watch → phone | `PAUSE`, `RESUME`, `FINISH` (Mirror mode). Phone applies it and the result comes back via `/session/state`. |
| `/session/ui-visible` | Message | either | tells the owner to start/stop ticks and unbatch/batch location |
| `/activity/transfer/{id}` | Channel | watch → phone | finished activity + points (gzip JSON) |
| `/activity/ack/{id}` | Message | phone → watch | phone has stored it; watch deletes its copy |

- The phone node is found with `CapabilityClient` (capability `pulsefit_phone`).
- The watch keeps finished activities in its own small Room database until acknowledged, and retries the transfer whenever the phone node becomes reachable.
- On receipt the phone stores the activity with `source = WATCH` and `PENDING_CREATE`, computes its `previewPolylines`, and enqueues sync. Transfers are idempotent by activity id.
- A command that gets no state change within 5 s shows "Phone not reachable" on the watch.

## 10. Settings

Account (email, sign-in method), units (metric / imperial), sync status with "Sync now", sign out, delete account, licences, app version.

## 11. Cross-cutting

- **Errors:** `Result<T, DataError>` end to end; ViewModels map errors to `UiText`; one-off messages go through an event channel to snackbars.
- **Configuration:** Maps API key, Supabase URL + anon key, Google web client id and API base URL come from `local.properties`/CI secrets via the secrets Gradle plugin; `debug` and `release` point to separate environments.
- **Accessibility:** content descriptions on all icon buttons, speed category never conveyed by colour alone (label + icon), touch targets ≥ 48 dp, dynamic type respected, TalkBack order verified on recording controls.
- **Localisation:** all strings in resources; formatters are locale- and unit-aware.
- **Privacy:** location is collected only during recording; analytics/crash reports must never contain coordinates. Play Data Safety form: precise location, email, user-generated content; account deletion available in-app.
- **Theming:** light and dark; dark map style in dark theme.

### 11.1 Large screens and foldables

The phone app runs on phones, foldables (folded and unfolded) and tablets, in portrait, landscape and split screen. With `targetSdk` 36+ the system ignores orientation locks and resizability opt-outs on large screens, so every screen must work at any window size.

**Window classes.** Layout decisions use the window size class of the current window, never the device type: width *compact* < 600 dp, *medium* 600–839 dp, *expanded* ≥ 840 dp; height *compact* < 480 dp (phone landscape). A folded phone is compact; an unfolded book-style foldable is medium in portrait and expanded in landscape.

**Activity.** `MainActivity` is resizable, declares no `screenOrientation`, and handles `screenSize|smallestScreenSize|screenLayout|orientation|density` in `configChanges` so fold, unfold and rotation do not recreate it (the map is not reloaded). Screen state still lives in ViewModels and `SavedStateHandle`, so recreation and process death lose nothing.

**Per screen.**

| Screen | Compact width | Medium / expanded width, or compact height |
|---|---|---|
| Home + Detail | One pane at a time | Home and Detail are panes of one `NavigableListDetailPaneScaffold`. Expanded width shows both (list ≈ 360–412 dp, detail fills the rest); with nothing selected the detail pane shows a "Select an activity" placeholder. Medium shows one pane. Back moves from the detail pane to the list. Deleting the shown activity returns the detail pane to the placeholder. |
| Recording | Map with bottom panel (§4.5) | Map with a side panel (360 dp) on the end side holding the metrics and controls; metric sizes are not reduced. Used for expanded width and for compact height. |
| Summary | Single column | Two columns: map on the start side; title, stats and Save/Discard on the end side. |
| Start type picker, filters | Modal bottom sheet | Centred dialog, max width 560 dp |
| Detail content | 16:9 map header, 2-column stats grid | Map header capped at 40 % of window height; 3 columns (medium) or 4 (expanded) |
| Auth, Settings | Full width | Single column, max width 600 dp, centred |
| Full-screen map | — | Same layout; the legend never covers the controls |

The Home list keeps a max content width of 840 dp when it is the only pane. Panes avoid a separating hinge (the adaptive scaffold's default hinge policy); nothing else reacts to fold posture.

**Continuity.** Folding, unfolding, rotating or resizing never interrupts a recording (the session is owned by `TrackingService`) and keeps: the camera-follow mode, open dialogs and sheets, text being typed, list scroll position, and the selected activity when switching between one and two panes.

## 12. Acceptance checks (device level)

| Check | Expected |
|---|---|
| No session running | No location request registered (`adb shell dumpsys location` shows none for the app); no foreground service |
| Paused session | Location request removed; notification shows Paused |
| Screen off for 10 min while cycling | Track is continuous; fixes arrived in batches |
| Kill the app during recording | Reopening restores the session in Paused with all points up to the last flush |
| Airplane mode: record, edit title, delete another | All visible locally at once; after reconnecting the backend matches within one sync run |
| Start on watch with phone off | Activity recorded; appears on the phone and backend after reconnecting |
| Start on phone, pause from watch | Phone pauses within 2 s; both show Paused |
| Unfold and fold a foldable while recording | Recording continues without a gap in the track; the layout switches between bottom and side panel; no dialog or typed text is lost |
| Unfolded foldable in landscape, open an activity | List and detail shown side by side; folding shows the same activity alone; Back returns to the list |
| Split screen 50/50 and rotate, on every main screen | No clipped content, overlapping controls or horizontal scrolling |
