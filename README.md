# CORE Heat

Heat training data fields for the Hammerhead Karoo, driven by a
[CORE](https://corebodytemp.com) body temperature sensor. Core and skin temperature, the Heat
Strain Index read straight from the sensor, CORE's heat zones, today's heat training load and your
heat adaptation score — drawn large in DIN 1451 Mittelschrift, so the number fills the
field, and colored in CORE's own zone colors.

Built on Hammerhead's [karoo-ext](https://github.com/hammerheadnav/karoo-ext) SDK.

Every field can be seen drawn, with its settings to try, on the
**[project page](https://angkyria.github.io/karoo-core/)**.

## Fields

All fields appear in the field picker under **CORE Heat**.

| Field | Shows |
|---|---|
| **Heat - Core Temp** | Core body temperature, colored by the current heat zone |
| **Heat - Skin Temp** | Skin temperature, colored by the current heat zone |
| **Heat - Strain Index** | Heat Strain Index (HSI), 0–10 |
| **Heat - Zone** | CORE heat zone, 1–4 |
| **Heat - Training Load** | Today's heat training load, 0–10 |
| **Heat - Adaptation** | Heat Adaptation Score, 0–100 % |
| **Heat - HUD Zone** | Core and skin side by side, the heat zone and index in a pill between them |
| **Heat - HUD Training Load** | Core and skin side by side, today's heat training load between them |
| **Heat - HUD Adaptation** | Core and skin side by side, the adaptation level and score between them |

Every field needs a CORE sensor paired to the Karoo. The Karoo passes on its core and skin
temperature; everything else is worked out by CORE Heat, following CORE's own definitions:

- **Heat - Strain Index** is the sensor's own. The CORE broadcasts it, but the Karoo does not pass
  it on, so CORE Heat connects to the sensor over Bluetooth to read it. Until that connection is
  up, or without it, the index is estimated from core and skin temperature using the zone chart
  CORE publishes.
- **Heat - Zone** is CORE's four zones on the index: 1 (0–0.9), 2 (1.0–2.9), 3 (3.0–6.9, the one
  to train in) and 4 (7.0+).
- **Heat - Training Load** is today's total, built up from time spent at an elevated HSI. Like
  CORE, it only counts while a ride is recording with a heart rate coming in.
- **Heat - Adaptation** is raised by days with a load above 2 and decays after two days without
  one. It is colored by CORE's four levels: Thermal Rookie, Heat Accustomed, Heat Adapted and
  Heat Champion.

CORE does not publish the formulas for the load and the score, so those are models fitted to the
tables and worked examples on its help centre; they land within a few tenths of CORE's own numbers
there. The score starts at 0 when CORE Heat is installed and only sees rides recorded on this
Karoo, so it will differ from the CORE app's if you also heat train elsewhere.

### HUD

The three **HUD** fields are one tile each: core and skin temperature side by side, each centred
in its half and colored by the heat zone like its own field, with a hairline between them. The top
row sets CORE and SKIN centred over their numbers, and between them a pill with the tile's one
icon and the metric the HUD is named for. The zone and adaptation pills light one square per zone
or level, up to the current one, beside the value: the Heat Strain Index for the zone, the score
for adaptation. The training load pill lights a square past each of 2, 4, 6 and 8, so its first
square comes on when the day starts counting toward adaptation. CORE gives the load no colors, so
its squares take a violet of their own, deeper with each step; the load's own field stays
uncolored.

On a half-width tile there is no room for the labels beside the pill, so the pill takes the top
row alone; CORE is still on the left and SKIN on the right.

## Reading the index from the sensor

Grant Bluetooth permission in the **CORE sensor** card of the CORE Heat app (main menu → CORE
Heat). The same card shows whether the index on screen is the sensor's or the estimate.

- The sensor's index needs CORE firmware **0.8.7 or later**.
- CORE Heat only uses a sensor once its core and skin temperatures match the Karoo's own reading,
  so on a group ride it will not latch onto a riding partner's CORE.
- A CORE accepts up to three Bluetooth connections. If the Karoo, your phone and a watch already
  hold them, pair the CORE to the Karoo over ANT+ to leave one free.

## FIT file

The Karoo records a CORE's core and skin temperature in the ride's FIT file itself. CORE Heat adds
the heat data it cannot record, as developer fields:

- **every second:** the index as `heat_strain_index` — the field name CORE asks recording devices
  to use — when it comes from the sensor, or as `estimated_heat_strain_index` when it is the
  estimate, so no analysis tool mistakes one for the other; and `heat_zone`.
- **in the ride summary:** `heat_training_load` (this ride's share of the day),
  `heat_adaptation_score`, `avg_heat_strain_index`, `max_heat_strain_index` and
  `time_in_heat_zone_1` to `_4` in seconds.

A ride without a CORE gets none of these.

## Settings

Open the CORE Heat app from the Karoo's main menu.

- **Heat colors** — *Off*, *Number* (the value itself is colored) or *Field background* (the whole
  field is filled and the number set in black or white).
- **Raised decimals** — draws the decimal small and raised, `38⁶` instead of `38.6`, giving the
  number more room.

## Installation

### Karoo 3 (Companion app)

1. Open the [latest release](https://github.com/angkyria/karoo-core/releases/latest) in your
   phone's browser.
2. Long-press the `core-release.apk` link and share it with the Hammerhead Companion app.
3. Your Karoo shows an install prompt — press **Install**.

### Karoo 2 (manual sideload)

1. Download `core-release.apk` from the [latest release](https://github.com/angkyria/karoo-core/releases/latest).
2. Set up your Karoo for sideloading — DC Rainmaker has a
   [step-by-step guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html).
3. `adb install core-release.apk`

### Adding fields to a ride profile

On the Karoo: **Settings → Profiles → your profile → Data Pages → pick a page → Add Field →
CORE Heat → choose a field.**

To update later, long-tap the CORE Heat icon on the main menu and select **Update**.

## Build from source

```
./gradlew :core:assembleDebug
adb install -r core/build/outputs/apk/debug/core-debug.apk
```

Tests:

```
./gradlew :core:testDebugUnitTest
```

## Project page

The [project page](https://angkyria.github.io/karoo-core/) is served by GitHub Pages from `docs/`
on `main`, so every push there publishes it. Its fields are drawn in the browser by
`docs/assets/site.js`, a port of the extension's renderer and heat model: a change to either in
`core/` belongs in that file too, or the page shows fields the Karoo no longer draws. To preview
it:

```
python3 -m http.server -d docs
```

and open `http://localhost:8000/`.

## Release signing

Every release has to be signed with the same key, forever: Android refuses to install an update
signed with a different one. Keep the keystore backed up somewhere outside this machine — losing
it means no existing install can ever be updated again.

Create the key once:

```
mkdir -p ~/keys
keytool -genkeypair -v -keystore ~/keys/karoo-core.jks \
    -alias karoo-core -keyalg RSA -keysize 4096 -validity 10000
```

Then copy `keystore.properties.template` to `keystore.properties` and fill it in. Both the
keystore and that file are gitignored; neither belongs in the repository.

```
./gradlew :core:assembleRelease
```

produces a signed APK under `core/build/outputs/apk/release/`. Without `keystore.properties` the
same command still works but leaves the APK unsigned, so anyone can build the project without
holding the key.

## Publishing a release

1. Bump `versionName` and `versionCode` in `core/build.gradle.kts`, `latestVersion`,
   `latestVersionCode` and `releaseNotes` in `core/manifest.json`, and the entry in `CHANGELOG.md`.
2. `./gradlew :core:assembleRelease`, then `scripts/verify-release-apk.sh`.
3. Tag and create the release with `core-release.apk` **and**
   `core/manifest.json` attached as `manifest.json`.

**`manifest.json` has to be ON the release, not just in the repository.** The Karoo's extension
library reads `releases/latest/download/manifest.json`; when that returns 404 riders cannot open
the extension's settings and are offered no update, while the APK looks perfectly healthy.

## Licenses

Apache-2.0 — see [LICENSE](LICENSE).

The HUD follows the HUD of [karoo-bignum](https://github.com/smartycoder/karoo-bignum), also
Apache-2.0: its layout of two whole fields and a zone pill, and the pill's drawing.

Every number and label is set in **DIN 1451 Mittelschrift**, the German road-sign typeface, as
[u_DIN 1451 Mittelschrift](https://fontlibrary.org/en/font/u-din-1451-mittelschrift): usr_share's
version of Peter Wiegel's free *Alte DIN 1451 Mittelschrift*. It is the only font bundled and there
is no font setting. It is © 2009-2016 Peter Wiegel and © 2016 usr_share, licensed under the SIL
Open Font License 1.1 — see [OFL.txt](OFL.txt).
