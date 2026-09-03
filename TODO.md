# Phase 1 — Manual logging

Tracks the phase 1 scope from the parity plan: making the app usable by someone with no
CGM. Checked items are built, building, and verified on an API 35 emulator.

285 unit tests and 5 instrumented tests, all green.

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

- [x] 285 unit tests, all green
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

# Phase 2 — Remote sources

All three remote sources — Nightscout, Dexcom Share, LibreLinkUp — plus background sync
and the reminders behind it. Nightscout is verified against a real site; the other two
have never met a live account.

## Nightscout

- [x] URL normalization, access-token and API_SECRET auth, unauthenticated fallback
- [x] Capability probe against the three real endpoints, not `/status`
- [x] Entries (JSON and tab-separated), treatments, `profile.json`, `devicestatus` for IOB/COB
- [x] Source stitching at read time, so switching source never hides stored history
- [x] Token in EncryptedSharedPreferences, never in the settings blob, masked on screen
- [x] **Verified against a real site** — 23 readings, 11 events and insulin doses came down
- [x] **Verified value-for-value against the test instance** (`boostt1d.nightscoutpro.com`,
      90 days of synthetic data): latest reading, 24h statistics (average, GMI, CV, all
      four range bands), 3-day event totals, today's individual boluses, and the full
      basal / carb-ratio / ISF schedules all match the server exactly; Room holds 4,031
      rows, precisely the 14-day cap, every one tagged `nightscout`
- [x] Sync fires the moment a remote source is configured, not only at launch — a fresh
      setup used to land on an empty dashboard with no sync until the app was restarted
- [x] A site with years of history is never asked for more than the retention window —
      `hoursToFetch` caps a first sync at 14 days and a routine one at the gap since the
      newest stored reading, plus an hour of overlap

## Dexcom Share

- [x] Login across every host and application ID for the region, by-id and by-name paths
- [x] Integer and string trend forms, four timestamp formats
- [x] Region as a first-class field, because the hosts do not federate
- [x] Password in EncryptedSharedPreferences, masked on screen
- [ ] **Verify against a real account.** The login dance is the part most likely to need
      adjusting, and it has never run against live Share.

## LibreLinkUp

- [x] Login with LibreView's regional redirect followed once and the region saved
- [x] Terms-not-accepted and client-version-floor recognised as their own failures —
      neither can be fixed in this app, so neither is retried as if it were transient
- [x] Connections, the ~12h graph endpoint, the current reading winning a shared timestamp
- [x] `ValueInMgPerDl`, or `Value` converted from mmol/L when that is all a region sends
- [x] `Account-Id` as SHA-256 of the user id, `product`/`version` headers
- [x] Password in EncryptedSharedPreferences, masked on screen
- [ ] **Verify against a real account.** Abbott's version floor moves; the first real
      sign-in is the only way to learn whether `4.16.0` is still accepted.

## Background sync and reminders

- [x] WorkManager periodic sync, 15-minute floor, network-constrained, off in manual mode
- [x] Stale reminder at three-quarters of the source's history window
- [x] Immediate reminder for rejected credentials
- [x] Notification permission and battery optimisation surfaced on the Data Source screen
- [ ] Verify a reminder actually fires — needs a device left alone for 18 hours
- [ ] Initial-download progress screen for a first sync of a full retention window

## Stored data

- [x] `StoredShapeTest` — older stored JSON keeps decoding when a shape gains a field, so
      a returning user is never sent through setup as though they were new

---

# Phase 3 — The engine

The therapy engine, ported test-first in dependency order. A module is checked only when its
iOS test file is ported and green; the order is the leaf-to-root order of the engine's own
dependency graph, so nothing is built on a module that is not yet proven.

## 3a — engine (no UI)

- [x] `MealOutcomeBuilder` ← MealOutcomeBuilderTests — leaf; meals joined to their glucose curves
- [x] `TherapyChangeDetector` ← TherapyChangeDetectorTests — leaf; notices a setting changed.
      Persistence sits behind `TherapySnapshotStore` so `engine/` stays Android-free; the
      Nightscout `profile.json` parser now keeps every document with its dates, because that
      list *is* the edit history and flattening it had thrown the history away
- [x] `WhatHappenedPatternDetector` + `PatternService` ← PatternClaimIntegrityTests,
      MealWindowPatternTests, WhatHappenedDurationLabelTests. Not a mutual dependency after
      all — the detector only *mentions* the service in a comment — so the detector is a leaf
      and the service layers on it. AI wording is gated off exactly as on iOS
      (`aiPatternWordingEnabled = false`); the merge that would apply it arrives in phase 4
- [x] `TherapyChangeOutcomeBuilder` ← TherapyChangeOutcomeBuilderTests — "did it work?"
- [x] `GlucoseWeeklyReportBuilder` ← GlucoseWeeklyReportComparisonTests — leaf. Reuses the
      statistics card's very-high constant rather than declaring a second 250
- [x] `WhatHappenedDailyOverviewBuilder` — leaf, no iOS test file; pinned here with nine
- [x] `FormulaInsightBuilder` + `PatternInsight` — leaf, no iOS test file; pinned here with eight
- [x] `InsulinDeliveryContext` — leaf, no iOS test file; pinned here with eight. The caller
      passes the therapy type from the profile where iOS reads UserDefaults
- [x] `NightscoutOnBoard` ← NightscoutOnBoardTests. The phase-2 walker read three shapes and
      needed IOB and COB on the same row; `parseOnBoard` now delegates to the ported walker
- [x] `DailyTherapyReviewService` + `DailyTherapyReviewCache` ← both test files. The network
      trip is a `Reviewer` interface, null in this build; assembly, the narrative guard and the
      once-a-day gate are complete and tested
- [x] `WhatHappenedAnalysisCache` ← WhatHappenedAnalysisCachePersistenceTests — behind
      `AnalysisCacheStore`; the app supplies the cache-directory file
- [ ] `TherapyAnalysisCache` — deferred to phase 4: it caches `AITherapySuggestions`, the AI
      dose-analysis response, which does not exist until the AI path does
- [x] `DoseSuggestionService` — no iOS test file; gated by `HIDE_DOSE_RECOMMENDATIONS` and
      pinned here with eleven tests, one of which asserts the flag is still on
- [x] `TherapySettingsReviewBuilder` ← TherapySettingsReviewBuilderTests (816 lines) — the
      crown jewel. Ported before the daily review rather than after it, because the daily
      review is built *from* its findings; all 26 cases green on first compile
- [ ] `DiabetesProfileService` — app-side orchestration, not engine: on Android it is
      `ProfileRepository` + `fetchProfileDocuments`. What remains is wiring — record a detector
      snapshot on every local profile save, feed fetched history in on sync (see 3b)
- [ ] `AIGlucoseAnalysisService` — deferred to phase 4 with the rest of the AI path
- N/A `DefaultsHygieneTests` — iOS UserDefaults hygiene; DataStore has no equivalent problem

## 3b — screens on top

- [x] `WhatHappenedReportView` (2,032) — the week in review, four pages: Summary, Patterns,
      Therapy, Days. `insights/` package; one page composed at a time, as on iOS
- [x] Review sections and outcome section — `TherapySettingsReviewSection` with the hourly
      strip and finding cards, `DailyTherapyReviewSection` with proposal rows, the "Did your
      changes work?" tile, and the three pushed screens (proposal, outcomes list, one outcome).
      `PatternInsightCard` is dead on iOS and skipped
- [x] Insights tab restored to the shell. Opens the report directly; on iOS it is a submenu with
      the Doctor Visit report, which returns in phase 5
- [x] Wiring: `WhatHappenedReportLoader` is the computation half of iOS's `refreshReport`, pure
      and tested end to end; the sync feeds every fetched profile document to the change
      detector; a hand-entered profile records a snapshot on save; the analysis cache lives in
      the cache directory and repaints the last result on launch
- [ ] "Advanced details" toggle belongs in Profile → Reports as on iOS; it is on the Therapy
      page for now
- [ ] Verify the report on the emulator against the test instance
- [ ] `MultiDayOverlayChartView` — the AGP profile. Used by the BG Log chart and the Doctor Visit
      report on iOS, so it goes with those rather than with this screen
- [ ] Ask for the insulin therapy type (loop, pump, injections) in Profile, as iOS does; the
      field exists and the review reads it, but nothing sets it yet
