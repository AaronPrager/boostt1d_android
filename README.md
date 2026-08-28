# BoostT1D for Android

A separate Android codebase, not a shared one. The iOS app at `../BoostT1D_IOS` stays
the source of truth for behaviour; this repo re-implements it in Kotlin and Compose.

## What is here

Setup, then an empty dashboard, then a profile you can edit. That is the whole app so far.

**Onboarding** — six full-screen steps, matching iOS: personal info, diabetes, photo,
region & units, connection, agreements. One header, one progress bar and one pair of
buttons are shared by every step; steps supply fields only.

**Connection is manual only.** iOS offers Nightscout, Dexcom Share and FreeStyle Libre
here, each with credentials and a Test connection button. None of those sync services
are ported, so none are offered — an option that cannot work is worse than an option
that is not shown. `GlucoseConnectionOption` keeps all four cases so stored settings
survive a round trip when the others arrive; `selectableInThisBuild` is what the UI reads.

**Dashboard** — deliberately empty. The iOS dashboard shows current glucose with trend,
time in range, insulin on board and today's carbs; all of it needs readings, and nothing
can produce any yet. It shows your target range and a Profile button.

**Profile** — every value setup collected, editable, re-validated by the same rules that
gated Continue.

## What is deliberately missing

- Manual **logging** — the one thing a manual-mode user actually needs. Without it the
  dashboard has no path to ever being non-empty.
- Sync services, food photos, insights, reports, notifications, background work.
- The **Privacy Policy and Terms of Use text**. Both consent checkboxes currently link
  to a stub saying so. This must be fixed before any release: a consent checkbox over a
  placeholder is worse than no checkbox.
- The demographics POST that iOS sends on marketing opt-in. There is no Android
  registration endpoint yet; iOS posts to `/api/ios/register-profile`.

## Layout

```
app/src/main/java/com/boostt1d/android/
├── data/          Domain types, DataStore persistence, unit conversion. No Compose.
├── onboarding/    Setup flow — model, validation, view model, chrome, six steps
├── dashboard/     The empty dashboard
├── profile/       Profile editing
└── ui/            Theme (ported from BoostTheme.swift) and shared components
```

`data/` and `onboarding/OnboardingModel.kt` hold no Android or Compose imports. That is
on purpose — it is what keeps the rules testable on a plain JVM, and what would let this
layer become a Kotlin Multiplatform module later without being rewritten.

## Parity notes

- **Validation is a 1:1 port** of `OnboardingValidation.swift`, including the split
  between `canContinue` (is it filled in) and `problem` (is it the right shape).
  `OnboardingValidationTest` pins the rules. When a rule changes on iOS it has to
  change here — that test is the only thing standing between two codebases and a
  silent disagreement about who may finish setup.
- **Glucose is stored in mg/dL always**, whatever the user reads, exactly as on iOS.
  `GlucoseDisplay` is the only place that knows about the user's unit.
- **Ages and durations are stored as instants**, not as the "30" and "3-9" they were
  entered as, so a profile keeps meaning as time passes.
- **The avatar is capped at 512px / JPEG 80** before storage, matching the iOS rule.
- Persistence is DataStore holding two JSON values, mirroring how iOS keeps the profile
  and settings in UserDefaults. Room arrives with readings, not before.

## Building

Needs Android Studio, or the Android SDK plus a JDK 17–21.

The Gradle wrapper JAR is not committed. Either open the project in Android Studio,
which regenerates it, or run once with a local Gradle:

```bash
gradle wrapper --gradle-version 8.9
```

Then:

```bash
./gradlew :app:assembleDebug
```

Unit tests need no emulator or device:

```bash
./gradlew :app:testDebugUnitTest
```

minSdk 26, targetSdk 35, Kotlin 2.0.21, AGP 8.7.3, Compose BOM 2024.10.01.

**This project has never been compiled.** It was written against the iOS source on a
machine with no Android SDK. Expect the first build to surface import and API-signature
fixes.
