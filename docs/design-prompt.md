# Design Generation Prompt

Paste the block below into the design tool (Figma Make, Stitch, or similar). It is self-contained. If the tool has a length limit, send "Brief + Visual direction + Components" first, then the phone screens, then the large-screen layouts, then the watch screens.

---

```text
Design a mobile app and a companion smartwatch app called "PulseFit".

## Brief
An Android app for recording outdoor activities with GPS: walking, running, inline skating and cycling. The user picks an activity type, presses Start, and sees their route drawn live on a map together with time, distance and speed. Afterwards the activity is saved with a map image of the route and appears in a history list. A Wear OS watch app offers the same recording controls on the wrist. Audience: casual to regular exercisers, used outdoors, in motion, often in bright sunlight and with one hand.

## Platform and format
- Android phone, Material 3 (Material You) components and spacing, frame 412 × 915 dp. Provide every screen in light and dark theme.
- Large screens and foldables (see "Large-screen layouts" below): unfolded foldable in landscape, frame 840 × 700 dp (expanded width); phone in landscape, frame 915 × 412 dp (compact height).
- Wear OS, Material 3 for Wear OS, round 454 × 454 px display (also check 384 px), dark theme only, content kept inside the round safe area.
- Use realistic sample content (titles like "Morning Run", "Evening Ride", distances 2–40 km, real-looking street maps). No lorem ipsum.

## Visual direction
- Energetic but calm and legible; the map and the numbers are the heroes, chrome stays quiet.
- Primary colour: a vivid green-teal. Neutral, slightly cool surfaces. Generous corner radii (16–28 dp), soft elevation.
- Typography: a clean geometric sans. Live metrics use very large tabular numerals (duration at 56–64 sp) so digits do not jump while ticking.
- One icon per activity type, used consistently everywhere (list rows, chips, type picker, watch): walking, running, inline skating, cycling. Each type also has its own accent tint.
- Speed has three categories that must be recognisable at a glance and never by colour alone: Slow (blue, single chevron), Average (green, double chevron), High (orange-red, triple chevron). The same three colours draw the route line on every map, so a route shows where the user was slow or fast.
- Maps: muted, low-saturation map style so the coloured route stands out; dark map style in dark theme. Route line 6 dp with rounded caps, a start marker (ring) and an end marker (flag).
- Accessibility: contrast at least 4.5:1 for text, touch targets at least 48 dp on phone and 52 dp on watch, primary recording controls at least 72 dp and reachable with a thumb.

## Shared components (build these first, as a small library)
Activity type chip and icon set; speed category chip (3 variants); metric tile (label, big value, unit); activity list row; day section header; filter chip; primary Start FAB (extended); round recording control buttons (Pause, Resume, Finish); "recording in progress" bar; sync status indicator (synced / pending / failed); empty-state illustration style; snackbar; confirmation dialog; bottom sheet.

## Phone screens

1. Welcome: logo, one-line value statement, "Continue with Google" button, "Sign up with email" button, "I already have an account" link.
2. Sign up with email: email, password, confirm password, inline validation errors, password rules hint, primary button, loading state.
3. Sign in: email, password, "Forgot password?", primary button, Google button, error state for wrong credentials.
4. Forgot password and "Check your email" confirmation screen.
5. Home (activity history):
   - Top bar with a search field, a filter icon with a badge showing the number of active filters, and the user avatar.
   - Row of removable chips for active filters under the top bar.
   - List grouped by day with sticky headers: "Today", "Yesterday", "Mon, 12 Oct". Each header shows that day's total distance and time.
   - Each row: small rounded map thumbnail of the route, activity type icon, title, start time, distance, duration, average speed, and a subtle cloud icon if not yet synced.
   - Extended FAB "Start" bottom right.
   - Variants: loading skeleton; empty state ("Record your first activity" with illustration and arrow towards the FAB); no search results with "Clear filters"; offline banner "3 activities waiting to sync"; recording-in-progress bar replacing the FAB (shows type icon, live time, distance, and "Recording on watch" variant).
6. Start activity bottom sheet: four large selectable cards (Walking, Running, Inline skating, Cycling) in a 2 × 2 grid, the last used one preselected, a large Start button.
7. Filter bottom sheet: activity type multi-select chips; date range picker; distance range slider; duration range slider; speed category chips; recorded on (phone / watch); sort by (newest, oldest, longest distance, longest duration); "Reset" and "Show 24 results" buttons.
8. Location permission rationale: short explanation that location is used only while recording, illustration, "Allow" button. Also a variant for "precise location required".
9. Recording (the most important screen):
   - Full-bleed map with the speed-coloured route, start marker, a pulsing current-position dot, and a re-centre button.
   - Bottom panel (rounded top corners) with: huge duration, distance, current speed with the speed category chip, average speed.
   - Controls: large Pause button and a Finish button. 
   - Variants: acquiring GPS (panel shows "Finding GPS signal…" with progress, metrics dimmed); recording; paused (panel tinted, "Paused" label, Resume and Finish buttons, route shown with a gap where paused); GPS signal lost banner; finish confirmation dialog.
10. Ongoing notification (expanded and collapsed): type icon, time, distance, Pause/Resume and Finish actions.
11. Activity summary (after Finish): route fitted on a map card, editable title field prefilled "Morning Run", key stats grid, "Save" primary button, "Discard" text button, discard confirmation dialog.
12. Activity detail:
   - Large static map header (16:9) showing the route, with an expand affordance; top bar with back, edit (pencil) and delete (bin).
   - Title, type chip, date and time range.
   - Stats grid: distance, active time, elapsed time, average speed, max speed, average pace, elevation gain.
   - "Speed breakdown": a horizontal segmented bar showing share of time in Slow / Average / High with percentages and durations.
   - Footer details: recorded on phone or watch, sync status.
   - Variants: edit title dialog; delete confirmation dialog; route line drawn on a plain background when the map cannot load (also used for list thumbnails).
13. Full-screen route map: interactive map of the route with speed colours, a legend for the three speed categories, close button.
14. Settings: account card (avatar, email, sign-in method), units (km / miles), sync status with "Sync now", sign out (with warning dialog about unsynced activities), delete account (destructive, with typed confirmation dialog), about.

## Large-screen layouts (unfolded foldable 840 × 700 dp, phone landscape 915 × 412 dp)
The same components and content, rearranged for wide windows. Never just stretch the phone layout.
1. Home + Detail side by side (840 × 700, light and dark): activity list on the left (about 380 dp wide, with search, filter chips and the Start FAB inside the list pane), detail of the selected activity on the right with the map header capped in height and a 4-column stats grid; the selected row is highlighted. Variant: nothing selected, the right pane shows a quiet "Select an activity" placeholder with the route-line illustration style.
2. Recording (840 × 700 and 915 × 412, light and dark): map fills the window, metrics and controls move into a 360 dp side panel on the right with the same large numerals and 72 dp controls. Variants: recording and paused.
3. Activity summary (840 × 700): two columns, route map on the left, title field, stats and Save / Discard on the right.
4. Start activity and Filter as centred dialogs (max 560 dp wide) instead of bottom sheets (840 × 700).
5. Sign in and Settings (840 × 700): single centred column, max 600 dp wide.

## Watch screens (Wear OS, round)
1. Activity type picker: curved scrolling list of four large chips with icons.
2. Ready: activity icon and name, GPS status ("Finding GPS" / "GPS ready"), large round Start button.
3. Recording — three swipeable pages with page indicator:
   a. Metrics: duration largest in the centre, distance and current speed below, a thin ring or arc around the screen edge coloured by speed category with its label.
   b. Map: full round map with the coloured route and current position, minimal overlay showing time.
   c. Controls: two large round buttons, Pause (or Resume) and Finish.
   Variants: paused state, "Mirroring phone" indicator when the activity is recorded on the phone, "Phone not reachable" message.
4. Finish confirmation.
5. Summary: check mark, distance, time, average speed, "Saved – will sync with phone".
6. Ambient (always-on) mode: black background, outline-only duration and distance.
7. Location permission prompt screen.

## Deliverables
- A component library page, then all phone screens (light and dark) and all watch screens, grouped by flow: Auth, Home, Recording, Detail, Settings, Large screens, Watch.
- Colour, typography, spacing and radius tokens named so they can be mapped to a Compose Material 3 theme.
- A simple prototype flow: Welcome → Home → Start sheet → Recording → Paused → Summary → Home → Detail.
```
