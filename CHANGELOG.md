# Changelog

Notable changes per release. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each entry here should match the `releaseNotes` field in `core/manifest.json`, which is what the
Karoo shows in its own update flow.

## [Unreleased]

### Added

- Three HUD fields, modelled on karoo-bignum's HUD: core and skin temperature side by side in one
  tile, centred under their labels, with a pill between the labels for the heat zone and index
  (Heat - HUD Zone), today's heat training load (Heat - HUD Training Load) or the adaptation
  level and score (Heat - HUD Adaptation). On a half-width tile the pill takes the top row alone.

### Changed

- A raised decimal keeps a gap before it, in every field, so it no longer runs into the digit
  before it.

## [0.1.0] - 2026-09-27

### Added

- Six data fields under **CORE Heat** in the field picker: Heat - Core Temp, Heat - Skin Temp,
  Heat - Strain Index, Heat - Zone, Heat - Training Load and Heat - Adaptation.
- The Heat Strain Index is read from the CORE sensor over Bluetooth (firmware 0.8.7 or later),
  with an estimate from core and skin temperature until the connection is up. The sensor is only
  used once its readings match the Karoo's, so a riding partner's CORE is never picked up.
- Heat developer fields in every ride's FIT file: `heat_strain_index` (or
  `estimated_heat_strain_index`) and `heat_zone` per second, and training load, adaptation score,
  average and maximum index and time in each heat zone in the summary.
- Every number and label set in DIN 1451 Mittelschrift.
- A coloured app and extension icon, so CORE Heat shows up on the Karoo 2's white Extensions list.
- Settings for heat colors (off, number, field background) and raised decimals.

[Unreleased]: https://github.com/angkyria/karoo-core/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/angkyria/karoo-core/releases/tag/v0.1.0
