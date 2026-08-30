# Phase 1 — Manual logging

Tracks the phase 1 scope from the parity plan: making the app usable by someone with no
CGM. Checked items are built, building, and verified on an API 35 emulator.

105 unit tests and 5 instrumented tests, all green.

## Data layer

- [x] `NightscoutGlucoseEntry` / `NightscoutTreatment` / `TimeValue` wire types
- [x] Flexible serializers for Nightscout's inconsistent JSON (number-or-string fields)
- [x] `TherapyProfile` — the therapy settings shape, filled by hand or by sync
- [x] Room entity, DAO and database for glucose readings
- [x] Treatment store as one whole-file JSON write (temp file + rename)
- [x] 14-day retention, enforced on launch rather than on write
- [x] Export the Room schema and commit it — `app/schemas/` is the migration history
- [x] Editing an existing reading or treatment — tap a row you entered. Downloaded rows
      are not editable, because an edit here would be overwritten on the next sync
      without ever reaching Nightscout.

## Logic, ported 1:1 with its tests first

- [x] `GlucoseStatistics` — average, SD, CV, eA1C, GMI, time in/above/below range
- [x] `InsulinDoseSchedule` — segments, midnight wrap, daily basal total
- [x] `BolusCalculator` — carb bolus, correction, IOB subtraction, excess insulin
- [x] `TodaySoFarBuilder` — today against the same clock hours on completed days
- [x] `GlucoseSourceRank` / `GlucoseSourceStitch` — the order of truth
- [x] Split "very high" (≥250 mg/dL) out of time-above-range — shown as a subset under
      the three bands rather than a fourth slice that would not total 100

## Screens

- [x] Three-tab shell with floating submenus
- [x] Dashboard — current glucose, quick actions, today so far, 24h chart
- [x] BG Log — window selector, statistics card, chart, reading rows
- [x] Event Log — window selector, totals, treatment rows
- [x] Manual entry sheets for readings and events
- [x] Insulin Doses — basal, carb ratio and correction factor schedules
- [x] Bolus Calculator
- [x] About
- [x] Group log rows by day, with a per-day summary in the header
- [x] A line for dense series — decided from the data's median gap, so CGM data is
      joined and sparse manual points stay as dots
- [ ] Pan and zoom on the chart
- [x] Profile uses the same header as every other destination

## Quality

- [x] 105 unit tests, all green
- [x] Instrumented tests — 5, against a real SQLite database
- [ ] Instrumented *UI* tests — the screens are still only checked by hand
- [x] Verified at CGM scale — 4,032 readings through stitching, Room and retention.
      The chart's filtering and axis maths were recomputing on every frame and are now
      memoized.
- [x] Dark theme rendered and fixed — disabled buttons were painted in `neutral`, which
      is *lighter* than the dark ground, so a disabled button was the brightest control
      on the screen
- [x] Screen-reader semantics — consent rows are toggleable, segmented controls and
      tabs are selectable, dropdowns announce their current value
- [ ] An actual TalkBack run, listening to it rather than reading the tree
- [x] Readable-width cap now works — `fillMaxSize()` was pinning the minimum width, so
      the 560dp cap was silently ignored and fields stretched edge to edge
- [ ] Landscape is usable but cramped: the header takes half the height. Needs a
      shorter header in that orientation, not just a width cap.

## Release blockers

- [x] **Privacy Policy text** — ported from iOS, adapted where Android differs
      (Keystore not Keychain) and where this build has fewer features than iOS
- [x] **Terms of Use text** — ported from iOS
- [ ] Play Data safety declaration
- [ ] Play Health apps declaration
- [ ] Keep `hideDoseRecommendations` equivalent behaviour when AI arrives — nothing to
      do yet, since this build has no AI path at all

---

# Landed early — Nightscout (phase 2)

Built ahead of the plan's order. Listed separately because its verification is not done.

- [x] URL normalization, SHA-1 token auth, three auth shapes for glucose
- [x] Capability probe against the three real endpoints, not `/status`
- [x] Entries (JSON and tab-separated), treatments, `profile.json` parsing
- [x] Source stitching at read time, so switching source never hides stored history
- [x] Token in EncryptedSharedPreferences, never in the settings blob
- [x] Data Source screen, connection test, Sync now, dashboard staleness line
- [x] Sync on launch
- [ ] **Verify against a real site.** Only the failure paths have been exercised; the
      happy path has never run against actual Nightscout data.
- [ ] Background sync via WorkManager — sync currently happens on launch and on demand
      only, so a closed app catches nothing
- [ ] Battery-optimization exemption prompt, and a staleness notification
- [ ] Handle a site whose history is longer than the retention window without pulling
      all of it on first sync
