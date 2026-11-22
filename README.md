# Ciclo: a period and cycle tracker, free, on your phone

A free, open-source period and cycle tracker for Android and Windows. Every feature is free: no account, no ads,
no subscription. What you log stays on your devices; a backup, your other devices and a partner's summary travel
end-to-end encrypted, so no server can read them.

- **Today:** the day of your cycle, when your next period is due (or how late it is), whether you are in your
  fertile window, PMS days. One tap logs today's flow.
- **Calendar:** logged periods, expected periods for the year ahead, the fertile window, the ovulation day and PMS
  days, with a legend. Tap any day to log it; add a past period in one step.
- **Day log:** flow (spotting to heavy), pain, feelings, energy, sleep, skin, digestion and appetite, sex and
  drive, discharge, vulva and vagina, peeing, ovulation and pregnancy tests, medicine, exercise, life, menopause
  signs, and your own tags in any of them. Basal temperature (°C or °F), weight (kg or lb), a note. Hide the
  categories you don't use.
- **Predictions from your own history:** the average of your last 6 cycles (very short or long ones, most likely a
  missed log, are left out), your own period length, ovulation counted back from the next period. A temperature
  rise (three mornings above the six before) or a positive ovulation test moves the prediction. The app explains
  how each one is made. They are estimates, never birth control.
- **Insights:** average cycle and period, how much your cycles vary (with a note when they vary by 8 days or more),
  every cycle as a bar, the temperature curve with its cover line, weight over time, and patterns: what tends to
  come back at the same point of your cycle ("Cramps: usually on days 1–2, in 4 of your last 4 cycles").
- **Modes:** tracking, trying to get pregnant, pregnant (week and due date), perimenopause (hot flashes, night
  sweats and more in the log).
- **Birth control:** pill (pack day, active and break days, "Took my pill"), ring, patch, shot (next change), IUD,
  implant.
- **Reminders:** before your period, the fertile window, your pill (with a "Taken" button), changing a ring,
  patch or shot, a daily nudge to log. The lock screen shows only "Ciclo reminder".
- **Lock:** ask for the phone's own PIN, pattern or fingerprint to open Ciclo; hidden from the recent apps screen.
- **Partner:** share a summary, never your journal: only what you tick (next period and cycle day, fertile window,
  today's feelings and energy, pregnancy week). They need Ciclo too.
- **Report for a doctor:** your cycles, period lengths, what you logged and on how many days, temperatures and
  notes, printed or saved as a PDF. CSV export of every day.
- **Your devices:** link a second phone or a computer; backups with an optional password. English and Spanish.

Not in Ciclo: articles, an AI assistant or a symptom checker. Health text we can't stand behind is worse than none.

## Download
- Android: Google Play (coming soon) or the APK on the [releases page](https://github.com/kleiders3010/jurisgames/releases)
- Windows: installer on the [releases page](https://github.com/kleiders3010/jurisgames/releases)

## How it works
Ciclo shares its engine with [Tasks](https://github.com/kleiders3010/kleiders-tasks) and Sobres: devices reach each
other directly ([iroh](https://iroh.computer)), over the same Wi-Fi, through public Nostr relays, the Mainline DHT,
and a small mailbox; every layer only ever sees encrypted data (CRDTs, [Loro](https://loro.dev)). Everything shown
is worked out on the device from the days you logged (`crates/kt-tasks/src/cycle.rs`).

## Build
- Rust core (`crates/`): `cargo test --workspace`
- Android: `scripts/build-android-libs.sh`, then `cd app && ./gradlew :android:assemblePlayRelease`
- Desktop: `scripts/package-desktop.sh`

## Feedback
In the app: ☰ › Send feedback. If the app doesn't open at all, open an issue at
https://github.com/kleiders3010/ciclo/issues or email hello@jurisgames.com.

License: Apache-2.0.
