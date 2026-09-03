# Google Play declarations — draft

What the app actually does, written down so the Play Console forms can be filled in without
re-reading the code. Drafted from the code as of phase 5; anything marked **confirm** needs a
decision from the owner, usually about the backend, which this repo cannot see.

## Data safety

### Collected and stored on the device

| Category | Data | Purpose | Leaves the device? |
|---|---|---|---|
| Health info | Glucose readings (CGM and manual), insulin doses, carbs and meals, exercise events, therapy settings (basal, carb ratio, sensitivity, targets) | App functionality: dashboard, logs, insights, doctor visit report | Only as described below |
| Health info | Food photos | Carb estimation | Sent to the BoostT1D backend for analysis; the response is kept, the photo is kept only in the Food Log on the device |
| Personal info | Name, date of birth, gender, country/state, optional parent name and email, years since diagnosis | Personalisation; demographics registration | Sent once to the BoostT1D registration endpoint when the profile is completed, plus on marketing opt-in changes and deletion. **confirm** the Android route exists before release |
| Account credentials | Nightscout URL and token, Dexcom Share username/password, LibreLinkUp email/password | Fetching the user's own data | Sent only to the service they belong to. Stored in the app's private storage (`CredentialStore`) |

Not collected: location, contacts, device or advertising identifiers, app activity analytics,
crash logs (no analytics or crash SDK is integrated).

### Sent off the device

* **BoostT1D backend** (`boostt1d.com`): food photos for carb estimation; a week of
  glucose, treatments, food-log summary and the therapy profile for the once-daily AI therapy
  review (`/api/insights`). No name or account identifier accompanies either request.
  **confirm** the backend's retention policy for request bodies — the form asks whether data
  is stored server-side.
* **Nightscout / Dexcom Share / LibreLinkUp**: the user's own credentials, to read their own
  data. The app posts nothing back to Nightscout in this build.

### Security practices

* Encrypted in transit: yes — every endpoint is HTTPS.
* User can request deletion: yes — Profile → Delete everything clears all local stores and
  asks the registration endpoint to mark the profile deleted.
* Data is not sold and is not used for advertising.

## Health apps declaration

* Category: Health & fitness → medical, diabetes self-management companion.
* The app is **not a medical device** and gives no dosing instructions: dose recommendations
  are compiled out (`Config.HIDE_DOSE_RECOMMENDATIONS = true`). The calculator explains how a
  bolus is worked out; insights say what happened and what to discuss with the care team.
* Every report carries "Educational & informational only — not medical advice."
* Health Connect: not used. No sensitive permissions are declared (the camera is the system
  camera via a FileProvider, so no CAMERA permission).
* Sensitive-data disclosure: health data stays on the device except for the two backend calls
  above, which the in-app privacy text should describe. **confirm** the privacy policy URL.
