# Phase 1 — Manual logging

Tracks the phase 1 scope from the parity plan: making the app usable by someone with no
CGM. Checked items are built, building, and verified on an API 35 emulator.

## Data layer

- [x] `NightscoutGlucoseEntry` / `NightscoutTreatment` / `TimeValue` wire types
- [x] Flexible serializers for Nightscout's inconsistent JSON (number-or-string fields)
- [x] `TherapyProfile` — the therapy settings shape, filled by hand or by sync
- [x] Room entity, DAO and database for glucose readings
- [x] Treatment store as one whole-file JSON write (temp file + rename)
- [x] 14-day retention, enforced on launch rather than on write
- [ ] Export the Room schema (`exportSchema = true`) and commit it — it is the migration
      history, and it needs to exist before the first release, not after
- [ ] Editing an existing reading or treatment (today they can only be added and deleted)

## Logic, ported 1:1 with its tests first

- [x] `GlucoseStatistics` — average, SD, CV, eA1C, GMI, time in/above/below range
- [x] `InsulinDoseSchedule` — segments, midnight wrap, daily basal total
- [x] `BolusCalculator` — carb bolus, correction, IOB subtraction, excess insulin
- [x] `TodaySoFarBuilder` — today against the same clock hours on completed days
- [x] `GlucoseSourceRank` / `GlucoseSourceStitch` — the order of truth
- [ ] Split "very high" (>250 mg/dL) out of time-above-range — `VERY_HIGH_MGDL` is
      defined and unused, so the dashboard currently understates severity

## Screens

- [x] Three-tab shell with floating submenus
- [x] Dashboard — current glucose, quick actions, today so far, 24h chart
- [x] BG Log — window selector, statistics card, chart, reading rows
- [x] Event Log — window selector, totals, treatment rows
- [x] Manual entry sheets for readings and events
- [x] Insulin Doses — basal, carb ratio and correction factor schedules
- [x] Bolus Calculator
- [x] About
- [ ] Group log rows by day — both logs are a flat list, which stops reading well past
      about fifty entries
- [ ] Pan and zoom on the chart, and a line for dense series (deliberately dots only
      while every reading is manual and hours apart)
- [ ] Profile keeps its own back arrow inside the shell, which no other destination does

## Quality

- [x] 101 unit tests, all green
- [ ] Instrumented UI tests — there are none; every UI check so far has been by hand
- [ ] A run with a realistic dataset (14 days of CGM is roughly 4,000 readings) to see
      whether the list and chart hold up
- [ ] Dark theme has never been looked at, only written
- [ ] TalkBack pass — content descriptions are partial
- [ ] Landscape and tablet layouts unchecked

## Release blockers

- [ ] **Privacy Policy text.** The consent checkbox in setup links to a stub that says so.
      A checkbox over a placeholder records agreement to nothing.
- [ ] **Terms of Use text.** Same.
- [ ] Play Data safety declaration
- [ ] Play Health apps declaration
- [ ] Keep `hideDoseRecommendations` equivalent behaviour — never ship the prompt variant
      that strips medical disclaimers

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
