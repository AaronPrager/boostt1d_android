# Phase 1 — Manual logging

Tracks the phase 1 scope from the parity plan: making the app usable by someone with no
CGM. Checked items are built, building, and verified on an API 35 emulator.

308 unit tests and 5 instrumented tests, all green.

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
- [x] Pan and zoom on the chart — the iOS chart has no gesture either; dropped for parity
- [x] Profile uses the same header as every other destination

## Quality

- [x] 308 unit tests, all green
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
- [x] Landscape: the header collapses to one line at a smaller size in that orientation

## Release blockers

- [x] **Privacy Policy text** — ported from iOS, adapted where Android differs
      (Keystore not Keychain) and where this build has fewer features than iOS
- [x] **Terms of Use text** — ported from iOS
- [x] Play Data safety declaration — drafted in `docs/play-declarations.md`; two backend facts to confirm
- [x] Play Health apps declaration — drafted alongside
- [x] Keep `hideDoseRecommendations` equivalent behaviour when AI arrives — nothing to
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
- [x] Initial-download progress screen after onboarding and after a connection change (`InitialDataDownload`)

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
- N/A `TherapyAnalysisCache`, `AIGlucoseAnalysisService`, `analyzeTherapyAdjustments` — the dose-
      analysis AI path only feeds `TherapyAdjustmentView`, which is dead on iOS. Not ported
- [x] `DoseSuggestionService` — no iOS test file; gated by `HIDE_DOSE_RECOMMENDATIONS` and
      pinned here with eleven tests, one of which asserts the flag is still on
- [x] `TherapySettingsReviewBuilder` ← TherapySettingsReviewBuilderTests (816 lines) — the
      crown jewel. Ported before the daily review rather than after it, because the daily
      review is built *from* its findings; all 26 cases green on first compile
- [ ] `DiabetesProfileService` — app-side orchestration, not engine: on Android it is
      `ProfileRepository` + `fetchProfileDocuments`. What remains is wiring — record a detector
      snapshot on every local profile save, feed fetched history in on sync (see 3b)
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
- [x] "Advanced details" toggle in Profile → Reports, as on iOS
- [x] Verified on the emulator against the test instance: Summary matches the server exactly
      (TIR 60% · low 3% · high 26% · very high 11% · GMI 7.3%, recomputed from the same window
      over the API); Patterns, Therapy and Days render; outcome detail → list → report on Back;
      the Profile toggle reaches the Therapy page
- [x] `MultiDayOverlayChartView` — the AGP profile. Used by the BG Log chart and the Doctor Visit
      report on iOS, so it goes with those rather than with this screen
- [x] Insulin therapy type (loop, pump, injections) — already asked in Profile; the review and
      the daily review read it

---

# Phase 4 — Food & AI

Both AI features go through the BoostT1D proxy (`/api/food-analysis`, `/api/insights`); the
Gemini key never leaves the server. On iOS only two AI calls are live — the meal photo and the
once-daily therapy review — and the same two are live here.

## Food

- [x] Food log in Room (v2, auto-migrated): photo analyses, manual meals, and carbs imported
      idempotently from the Event Log ← EventLogFoodLogImportTests + the planner's own rules
- [x] Food Log screen (1 / 7 / 30 days, totals, thumbnails) and the shared entry editor (photo
      from camera or library, nutrition, insulin given, delete when editing)
- [x] Snap a Meal: photo → estimate → Current Data. The dose is withheld under
      `HIDE_DOSE_RECOMMENDATIONS`; the inputs are shown read-only, with IOB/COB marked unknown when
      the CGM connection is stale, as on iOS
- [x] Six free estimations a day, reset at local midnight, counted on the device
- [x] Upload sizing (≤320 KB) and row thumbnails (256 px, ≤80 KB) match the iOS limits
- [x] Food tab with its Snap a Meal / Food Log submenu, as on iOS
- [ ] The camera is the system camera via a FileProvider, not an in-app capture view. No CAMERA
      permission is declared, so none is asked for
- [x] The Bolus Calculator button prefills carbs, glucose, IOB and COB from the analysis, as iOS does
- [x] Verified on the emulator: the v1→v2 migration kept two weeks of readings; the Food Log
      shows the three carb rows imported from the test instance; a manual entry saved and
      appeared; Snap a Meal shows the six-a-day banner. The camera path needs a hand on a device

## AI

- [x] `BoostBackend` — Gemini-shaped bodies to the proxy; 5xx retried with backoff; the iOS
      status messages. Prompts reproduced verbatim (`AIPrompts`)
- [x] Once-daily therapy review wired: painted after the formula result, one attempt per
      calendar day, wording held while Therapy is on screen. `AI_INSIGHTS_ENABLED = true`
- [x] Food-response parser with every iOS fallback (fences, prose, carbs-from-text, direct body)
- [x] Pattern reviewer wired but gated off (`aiPatternWordingEnabled = false`), exactly as on iOS
- [x] Verified against the live backend: one POST to `/api/insights`, 200 in 21 s, the Therapy
      page opened with the model's overview. Found and fixed on the way: a cached open never
      refreshed, so the AI pass could not run until the refresh button was tapped
- [ ] Demographics registration — client ported (`RegistrationService`, best-effort); the server
      needs an `/api/android/register-profile` route (or a generalised one) before it succeeds

---

# Phase 5 — Doctor Visit report & the AGP chart

- [x] `DoctorVisitReportBuilder` — no iOS test file; pinned here (prior period vs half split, recurrent blocks, daily profiles)
- [x] `AgpProfile` — the 10-minute median / IQR maths shared by the on-screen chart and the PDF
- [x] `AgpChart` — `MultiDayOverlayChartView`: BG Log windows longer than a day, and the report
- [x] Doctor Visit screen: 7 / 14 days · Before Visit / Clinical / Details · questions persisted
- [x] PDF export via `PdfDocument`, four pages with continuation, shared through the FileProvider
- [x] Insights tab becomes a submenu — What Happened? and Doctor Visit — as on iOS
- [x] Verified on the emulator against the test instance: all three pages on live data, the 14-day
      switch, the share sheet with a 160 KB six-page PDF, and the AGP in BG Log's 3-day window


---

# Phase 6: catching up with iOS

Everything the parity sweep found missing, built in one pass. 344 unit tests, all green.

## Ported from iOS commits Android never saw

- [x] `BasalDeliveryCalculator`, basal delivered over a stretch of time, temp basals
      time-weighted and the profile rate filling every uncovered minute. `tempBasalIntervals`
      is now shared out of the review builder rather than copied, so one arithmetic serves
      both screens and a TDD cannot disagree between them
- [x] What Happened? day cards carry `basalInsulin` and a `totalDailyDose`. Today is only
      counted up to now: filling the rest of the day from the schedule would show insulin
      the user has not taken yet. The chip reads "TDD 30.0u" where basal is known and
      "6.0u bolus" where it is not, never one label for both
- [x] Doctor Visit: `basalUnits` and `isCompleteDay` per day, and the report's
      `averageTotalDailyDose`, `basalSharePercent` and `insulinSummaryLine`. The two days a
      period boundary cuts through are printed but never averaged. The daily column header
      says Bolus or TDD depending on whether basal could be reconstructed at all, on screen
      and in the PDF
- [x] `fat` and `protein` on `NightscoutTreatment`, carried into imported food-log rows and
      shown as their own badges. Trio, iAPS, AndroidAPS and Careportal write them
- [x] `observedValue` on `DailyTherapyProposal`, and the proposal screen now says why the
      suggestion stops short of it: half the gap, and never more than 20% in one review.
      `MAX_CHANGE_PERCENT` is no longer private because that sentence needs it
- [x] Analysis cache schema 4 → 5, since the persisted day shape gained a field

## Screens iOS had and this build did not

- [x] How It Works, the nine-page product tour, reachable from Menu at any time
- [x] Help, ten step-by-step guides, list and chapter. Wording follows Android where the
      two platforms differ: connection settings live on Data Source, not inside Profile, and
      secrets go to EncryptedSharedPreferences rather than the Keychain
- [x] Support Us, the donation page. `SUPPORT_PAGE_URL` had been sitting in `Config`
      unread since phase 1. Stripe handles checkout in a browser; no card detail reaches
      this process
- [x] Play In-App Review, the same cadence iOS uses with StoreKit: first ask at five
      qualified opens, then every twenty-five, never in the first three days, never twice in
      ninety days, never twice on one version. The decision is a pure `ReviewCadence` so it
      could be tested without a device
- [x] Coaching tips above the bottom bar. Android has no TipKit, so the parts of it the app
      used are reproduced: a tip waits for the tour to finish, shows at most twice, retires
      when dismissed, and only one appears on any given day. iOS declares two further tips
      that are never attached to a view; those are not ported
- [x] The glucose-only notice for Dexcom Share and LibreLinkUp. Both vendors have the same
      limitation so the copy is shared and only the product name changes. On the dashboard it
      explains the empty insulin and carb tiles, which used to claim a loop had not published
      yet. There is no loop to wait for. Also on Data Source while the source is being
      chosen, and on the Event Log, which iOS writes the copy for but never shows
- [x] The LibreLinkUp walkthrough behind "How do I set this up?". Abbott has no read API, so
      the setup is a sharing invitation and people get stuck on it
- [x] State, asked only of users in the United States. `state` had been on both `UserProfile`
      and the registration payload since phase 2 and was always sent null, because there was
      no field to fill it
- [x] "Is this the same person?" before a source switch. Answering no wipes stored data and
      disconnects the old account, credential included: dropping readings while leaving a
      Nightscout URL and token in settings still left a valid treatment source, and the next
      download pulled that account's event log straight back
- [x] About no longer describes the phase 1 build. It reads the version from the package and
      says what actually leaves the device

## Still open

- [ ] **Server route.** `RegistrationService` posts to `/api/android/register-profile`, which
      the backend does not serve. The client is finished; the route is not.
