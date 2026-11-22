//! Ciclo: a period and cycle journal (the cycle domain on top of a list document).
//!
//! Schema (root maps of the document):
//! - `cset`: settings. `mode` ("track", "conceive", "pregnant", "peri"), `clen` / `plen` (cycle and period length
//!   to assume until enough periods are logged), `lut` (days from ovulation to the next period, 14 unless set),
//!   `lmp` (pregnancy: first day of the last period, "YYYY-MM-DD"), `bc` (birth control: "", "pill", "ring",
//!   "patch", "shot", "iud", "implant"), `bcs` (the first day of the current pack, ring, patch or shot),
//!   `bca` / `bcb` (active days and break days of a pill pack, 21 / 7 unless set), `hide` (categories not shown,
//!   comma separated).
//! - `cday`: date "YYYY-MM-DD" -> map { `flow` (0 none, 1 spotting, 2 light, 3 medium, 4 heavy), `tags` (map tag id
//!   -> 1), `temp` (basal temperature in hundredths of a degree Celsius), `wt` (weight in grams), `note`,
//!   `bc` (1 = birth control taken that day) }.
//! - `ctag`: custom tag id ("c" + 16 hex) -> map { `name`, `cat`, `gone` (ms when deleted) }.
//!
//! Everything shown is computed from the days: periods, cycle lengths, predictions, the fertile window, the
//! temperature shift and the patterns are never stored, so two devices logging offline can't disagree once
//! they have each other's days. Predictions are estimates from the user's own history, never contraception.

use std::collections::{BTreeMap, BTreeSet};

use loro::{Container, LoroDoc, LoroMap, LoroValue, ValueOrContainer};
use rand::RngCore;

use crate::budget::{civil_date, civil_days, valid_date};
use crate::{Error, Result};

pub const MODES: [&str; 4] = ["track", "conceive", "pregnant", "peri"];
pub const BIRTH_CONTROL: [&str; 7] = ["", "pill", "ring", "patch", "shot", "iud", "implant"];
pub const MAX_NOTE: usize = 2000;
pub const MAX_NAME: usize = 60;
/// Most custom tags kept.
pub const MAX_CUSTOM: usize = 200;
pub const DEFAULT_CYCLE: i64 = 28;
pub const DEFAULT_PERIOD: i64 = 5;
pub const DEFAULT_LUTEAL: i64 = 14;
/// Cycles shorter or longer than this are left out of the averages (most likely a period that wasn't logged).
pub const CYCLE_MIN: i64 = 15;
pub const CYCLE_MAX: i64 = 60;
/// How many recent cycles the averages use.
pub const RECENT: usize = 6;
/// Flow days this many days apart or closer are one period (a day off in the middle doesn't end it).
pub const PERIOD_GAP: i64 = 2;
/// Days after the predicted start before a period counts as late while the cycles aren't learned yet.
pub const LATE_GRACE: i64 = 5;
/// A temperature rise that counts: the three days after it at least this much above the six before (hundredths °C).
pub const TEMP_RISE: i64 = 20;
/// Predictions reach this far ahead (days).
pub const AHEAD: i64 = 400;

/// The built-in tags: (category, id). Their words are the app's (English and Spanish); the document holds ids only.
pub const CATEGORIES: [(&str, &[&str]); 15] = [
    ("pain", &["cramps", "headache", "migraine", "ovulation", "breasts", "back", "joints", "stomach"]),
    ("feel", &["happy", "calm", "sensitive", "sad", "anxious", "irritable", "swings", "pms"]),
    ("energy", &["high", "normal", "low", "exhausted"]),
    ("sleep", &["good", "restless", "insomnia", "a_lot", "dreams"]),
    ("skin", &["good", "acne", "oily", "dry", "itchy"]),
    ("digest", &["bloating", "nausea", "cravings", "gas", "constipation", "diarrhea", "appetite_up", "appetite_down"]),
    ("sex", &["protected", "unprotected", "withdrawal", "solo", "drive_high", "drive_low", "pain"]),
    ("mucus", &["dry", "sticky", "creamy", "eggwhite", "watery", "unusual"]),
    ("vulva", &["itchy", "dry", "swollen", "odor", "pain"]),
    ("urine", &["often", "burning", "leaks"]),
    ("tests", &["ov_pos", "ov_neg", "preg_pos", "preg_neg"]),
    ("meds", &["painkiller", "antibiotic", "antihistamine", "vitamins", "iron", "other"]),
    ("move", &["walk", "run", "gym", "yoga", "swim", "bike", "sport", "rest"]),
    ("social", &["party", "friends", "alone", "stress", "travel", "alcohol"]),
    ("peri", &["hot_flash", "night_sweat", "brain_fog", "palpitations", "dryness", "hair"]),
];

pub fn builtin_tag(id: &str) -> bool {
    id.split_once('.').is_some_and(|(c, t)| CATEGORIES.iter().any(|(cat, tags)| *cat == c && tags.contains(&t)))
}

fn custom_id(id: &str) -> bool {
    id.len() == 17 && id.starts_with('c') && id[1..].bytes().all(|b| b.is_ascii_hexdigit())
}

fn known_cat(c: &str) -> bool {
    CATEGORIES.iter().any(|(cat, _)| *cat == c) || c == "mine"
}

// ---------- small readers ----------

fn s(m: &LoroMap, k: &str) -> String {
    match m.get(k) {
        Some(ValueOrContainer::Value(LoroValue::String(v))) => v.to_string(),
        _ => String::new(),
    }
}

fn i(m: &LoroMap, k: &str) -> i64 {
    match m.get(k) {
        Some(ValueOrContainer::Value(LoroValue::I64(v))) => v,
        Some(ValueOrContainer::Value(LoroValue::Double(v))) if v.is_finite() => v as i64,
        _ => 0,
    }
}

fn shown(t: String, max: usize) -> String {
    if t.chars().count() <= max && !t.chars().any(|c| c.is_control() && c != '\n') {
        return t;
    }
    t.chars().filter(|c| !c.is_control() || *c == '\n').take(max).collect()
}

fn clip(t: &str, max: usize) -> String {
    shown(t.trim().to_string(), max)
}

fn day_map(doc: &LoroDoc, date: &str) -> Result<LoroMap> {
    if !valid_date(date) {
        return Err(Error::Invalid("not a date".into()));
    }
    // mergeable (not get_or_create_container): two devices that log the same day apart get the same day map, so both
    // logs merge instead of one device's day replacing the other's
    Ok(doc.get_map("cday").ensure_mergeable_map(date).map_err(|e| Error::Invalid(e.to_string()))?)
}

fn n(d: &str) -> i64 {
    civil_days(d).unwrap_or(0)
}

// ---------- settings ----------

#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub mode: String,
    pub cycle_len: i64,
    pub period_len: i64,
    pub luteal: i64,
    pub lmp: String,
    pub bc: String,
    pub bc_start: String,
    pub bc_active: i64,
    pub bc_break: i64,
    pub hidden: Vec<String>,
}

pub fn settings(doc: &LoroDoc) -> Settings {
    let m = doc.get_map("cset");
    let within = |v: i64, lo: i64, hi: i64, d: i64| if (lo..=hi).contains(&v) { v } else { d };
    let mode = s(&m, "mode");
    let bc = s(&m, "bc");
    let date = |k: &str| Some(s(&m, k)).filter(|d| valid_date(d)).unwrap_or_default();
    Settings {
        mode: if MODES.contains(&mode.as_str()) { mode } else { "track".into() },
        cycle_len: within(i(&m, "clen"), CYCLE_MIN, CYCLE_MAX, DEFAULT_CYCLE),
        period_len: within(i(&m, "plen"), 1, 12, DEFAULT_PERIOD),
        luteal: within(i(&m, "lut"), 8, 20, DEFAULT_LUTEAL),
        lmp: date("lmp"),
        bc: if BIRTH_CONTROL.contains(&bc.as_str()) { bc } else { String::new() },
        bc_start: date("bcs"),
        bc_active: within(i(&m, "bca"), 1, 120, 21),
        // 0 is a real choice here (no break days): only a missing value takes the default
        bc_break: if m.get("bcb").is_some() { within(i(&m, "bcb"), 0, 14, 7) } else { 7 },
        hidden: s(&m, "hide").split(',').filter(|c| known_cat(c)).map(String::from).collect(),
    }
}

/// Change one setting. Numbers and dates are checked; an empty date or 0 clears it.
pub fn set_setting(doc: &LoroDoc, key: &str, value: &str) -> Result<()> {
    let m = doc.get_map("cset");
    let bad = || Error::Invalid(format!("bad value for {key}"));
    let num = |lo: i64, hi: i64| -> Result<i64> { value.parse::<i64>().ok().filter(|v| (lo..=hi).contains(v) || *v == 0).ok_or_else(bad) };
    let e = |r: std::result::Result<(), loro::LoroError>| r.map_err(|e| Error::Invalid(e.to_string()));
    match key {
        "mode" if MODES.contains(&value) => e(m.insert("mode", value)),
        "bc" if BIRTH_CONTROL.contains(&value) => e(m.insert("bc", value)),
        "clen" => e(m.insert("clen", num(CYCLE_MIN, CYCLE_MAX)?)),
        "plen" => e(m.insert("plen", num(1, 12)?)),
        "lut" => e(m.insert("lut", num(8, 20)?)),
        "bca" => e(m.insert("bca", num(1, 120)?)),
        "bcb" => e(m.insert("bcb", num(0, 14)?)),
        "lmp" | "bcs" if value.is_empty() || valid_date(value) => e(m.insert(key, value)),
        "hide" if value.split(',').all(|c| c.is_empty() || known_cat(c)) => e(m.insert("hide", value)),
        _ => Err(bad()),
    }
}

// ---------- days ----------

#[derive(Clone, Debug, PartialEq, Default)]
pub struct Day {
    pub date: String,
    pub flow: i64,
    pub tags: Vec<String>,
    /// Hundredths of a degree Celsius; 0 = not logged.
    pub temp: i64,
    /// Grams; 0 = not logged.
    pub weight: i64,
    pub note: String,
    pub bc_taken: bool,
}

impl Day {
    pub fn is_empty(&self) -> bool {
        self.flow == 0 && self.tags.is_empty() && self.temp == 0 && self.weight == 0 && self.note.is_empty() && !self.bc_taken
    }
}

fn temp_ok(t: i64) -> bool {
    (3000..=4500).contains(&t)
}

fn weight_ok(w: i64) -> bool {
    (1_000..=500_000).contains(&w)
}

fn read_day(date: String, m: &LoroMap, custom: &BTreeSet<String>) -> Option<Day> {
    if !valid_date(&date) {
        return None;
    }
    let mut tags = Vec::new();
    if let Some(ValueOrContainer::Container(Container::Map(t))) = m.get("tags") {
        t.for_each(|k, v| {
            let on = matches!(v, ValueOrContainer::Value(LoroValue::I64(1)) | ValueOrContainer::Value(LoroValue::Bool(true)));
            if on && (builtin_tag(k) || custom.contains(k)) {
                tags.push(k.to_string());
            }
        });
    }
    tags.sort();
    let flow = i(m, "flow");
    let (temp, weight) = (i(m, "temp"), i(m, "wt"));
    Some(Day {
        date,
        flow: if (0..=4).contains(&flow) { flow } else { 0 },
        tags,
        temp: if temp_ok(temp) { temp } else { 0 },
        weight: if weight_ok(weight) { weight } else { 0 },
        note: shown(s(m, "note"), MAX_NOTE),
        bc_taken: i(m, "bc") == 1,
    })
}

/// Every logged day, oldest first (days with nothing on them left out).
pub fn days(doc: &LoroDoc) -> Vec<Day> {
    let custom: BTreeSet<String> = custom_tags(doc).into_iter().map(|t| t.id).collect();
    let mut out = Vec::new();
    doc.get_map("cday").for_each(|k, v| {
        if let ValueOrContainer::Container(Container::Map(m)) = v {
            if let Some(d) = read_day(k.to_string(), &m, &custom) {
                if !d.is_empty() {
                    out.push(d);
                }
            }
        }
    });
    out.sort_by(|a, b| a.date.cmp(&b.date));
    out
}

pub fn day(doc: &LoroDoc, date: &str) -> Day {
    days(doc).into_iter().find(|d| d.date == date).unwrap_or(Day { date: date.into(), ..Default::default() })
}

pub fn set_flow(doc: &LoroDoc, date: &str, flow: i64) -> Result<()> {
    if !(0..=4).contains(&flow) {
        return Err(Error::Invalid("flow is 0-4".into()));
    }
    day_map(doc, date)?.insert("flow", flow).map_err(|e| Error::Invalid(e.to_string()))
}

/// Log a period over several days at once (the first `len` days from `start`, medium flow where nothing is logged).
pub fn log_period(doc: &LoroDoc, start: &str, len: i64) -> Result<()> {
    if !valid_date(start) || !(1..=12).contains(&len) {
        return Err(Error::Invalid("bad period".into()));
    }
    let custom = BTreeSet::new();
    for k in 0..len {
        let d = civil_date(n(start) + k);
        let m = day_map(doc, &d)?;
        let cur = read_day(d.clone(), &m, &custom).map(|x| x.flow).unwrap_or(0);
        if cur < 2 {
            m.insert("flow", if k == 0 || k + 1 < len { 3 } else { 2 }).map_err(|e| Error::Invalid(e.to_string()))?;
        }
    }
    Ok(())
}

pub fn set_tag(doc: &LoroDoc, date: &str, tag: &str, on: bool) -> Result<()> {
    if !builtin_tag(tag) && !custom_tags(doc).iter().any(|t| t.id == tag) {
        return Err(Error::Invalid("unknown tag".into()));
    }
    let m = day_map(doc, date)?;
    let tags = m.ensure_mergeable_map("tags").map_err(|e| Error::Invalid(e.to_string()))?;
    let r = if on { tags.insert(tag, 1i64) } else { tags.delete(tag) };
    r.map_err(|e| Error::Invalid(e.to_string()))
}

/// Basal temperature in hundredths of a degree Celsius (0 clears it).
pub fn set_temp(doc: &LoroDoc, date: &str, temp: i64) -> Result<()> {
    if temp != 0 && !temp_ok(temp) {
        return Err(Error::Invalid("temperature out of range".into()));
    }
    let m = day_map(doc, date)?;
    let r = if temp == 0 { m.delete("temp") } else { m.insert("temp", temp) };
    r.map_err(|e| Error::Invalid(e.to_string()))
}

/// Weight in grams (0 clears it).
pub fn set_weight(doc: &LoroDoc, date: &str, grams: i64) -> Result<()> {
    if grams != 0 && !weight_ok(grams) {
        return Err(Error::Invalid("weight out of range".into()));
    }
    let m = day_map(doc, date)?;
    let r = if grams == 0 { m.delete("wt") } else { m.insert("wt", grams) };
    r.map_err(|e| Error::Invalid(e.to_string()))
}

pub fn set_note(doc: &LoroDoc, date: &str, note: &str) -> Result<()> {
    day_map(doc, date)?.insert("note", clip(note, MAX_NOTE)).map_err(|e| Error::Invalid(e.to_string()))
}

pub fn set_bc_taken(doc: &LoroDoc, date: &str, taken: bool) -> Result<()> {
    let m = day_map(doc, date)?;
    let r = if taken { m.insert("bc", 1i64) } else { m.delete("bc") };
    r.map_err(|e| Error::Invalid(e.to_string()))
}

// ---------- custom tags ----------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CustomTag {
    pub id: String,
    pub name: String,
    pub cat: String,
}

pub fn custom_tags(doc: &LoroDoc) -> Vec<CustomTag> {
    let mut out = Vec::new();
    doc.get_map("ctag").for_each(|k, v| {
        if let ValueOrContainer::Container(Container::Map(m)) = v {
            let cat = s(&m, "cat");
            if custom_id(k) && i(&m, "gone") == 0 && known_cat(&cat) {
                let name = shown(s(&m, "name"), MAX_NAME);
                if !name.trim().is_empty() {
                    out.push(CustomTag { id: k.to_string(), name, cat });
                }
            }
        }
    });
    out.sort_by(|a, b| a.name.to_lowercase().cmp(&b.name.to_lowercase()));
    out.truncate(MAX_CUSTOM);
    out
}

pub fn add_custom_tag(doc: &LoroDoc, name: &str, cat: &str) -> Result<String> {
    let name = clip(name, MAX_NAME);
    if name.is_empty() || !known_cat(cat) {
        return Err(Error::Invalid("a tag needs a name and a category".into()));
    }
    if custom_tags(doc).len() >= MAX_CUSTOM {
        return Err(Error::Invalid("too many tags".into()));
    }
    let mut b = [0u8; 8];
    rand::thread_rng().fill_bytes(&mut b);
    let id = format!("c{}", hex::encode(b));
    let m = doc.get_map("ctag").ensure_mergeable_map(&id).map_err(|e| Error::Invalid(e.to_string()))?;
    let e = |r: std::result::Result<(), loro::LoroError>| r.map_err(|e| Error::Invalid(e.to_string()));
    e(m.insert("name", name))?;
    e(m.insert("cat", cat))?;
    Ok(id)
}

pub fn delete_custom_tag(doc: &LoroDoc, id: &str, now_ms: u64) -> Result<()> {
    match doc.get_map("ctag").get(id) {
        Some(ValueOrContainer::Container(Container::Map(m))) => m.insert("gone", now_ms as i64).map_err(|e| Error::Invalid(e.to_string())),
        _ => Err(Error::Invalid("no such tag".into())),
    }
}

// ---------- periods and cycles ----------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Period {
    pub start: String,
    pub end: String,
    pub days: i64,
}

/// Periods from the flow days: light flow or more; days at most [`PERIOD_GAP`] apart are one period. Spotting
/// alone never starts one.
pub fn periods(all: &[Day]) -> Vec<Period> {
    let mut out: Vec<(i64, i64)> = Vec::new();
    for d in all.iter().filter(|d| d.flow >= 2) {
        let x = n(&d.date);
        match out.last_mut() {
            Some((_, end)) if x - *end <= PERIOD_GAP => *end = x,
            _ => out.push((x, x)),
        }
    }
    out.into_iter().map(|(a, b)| Period { start: civil_date(a), end: civil_date(b), days: b - a + 1 }).collect()
}

#[derive(Clone, Debug, PartialEq)]
pub struct Stats {
    /// Lengths of the finished cycles, oldest first (start of one period to the start of the next).
    pub cycles: Vec<(String, i64)>,
    pub avg_cycle: i64,
    pub avg_period: i64,
    /// Shortest and longest of the recent cycles used for the average (0 when fewer than two).
    pub shortest: i64,
    pub longest: i64,
    /// From the user's own logs (at least two finished cycles), or from the settings.
    pub learned: bool,
    /// Recent cycles differ by 8 days or more.
    pub irregular: bool,
}

pub fn stats(all: &[Day], set: &Settings) -> Stats {
    let p = periods(all);
    let cycles: Vec<(String, i64)> = p.windows(2).map(|w| (w[0].start.clone(), n(&w[1].start) - n(&w[0].start))).collect();
    let used: Vec<i64> = cycles.iter().map(|c| c.1).filter(|l| (CYCLE_MIN..=CYCLE_MAX).contains(l)).rev().take(RECENT).collect();
    let learned = used.len() >= 2;
    let avg_cycle = if used.is_empty() { set.cycle_len } else { (used.iter().sum::<i64>() as f64 / used.len() as f64).round() as i64 };
    // the last period may still be going on: it counts only once it is over
    let lens: Vec<i64> = p.iter().rev().take(RECENT).map(|x| x.days).filter(|d| (1..=12).contains(d)).collect();
    let avg_period = if lens.len() >= 2 { (lens.iter().sum::<i64>() as f64 / lens.len() as f64).round() as i64 } else { set.period_len };
    let (shortest, longest) = if used.len() >= 2 { (*used.iter().min().unwrap(), *used.iter().max().unwrap()) } else { (0, 0) };
    Stats { cycles, avg_cycle, avg_period, shortest, longest, learned, irregular: learned && longest - shortest >= 8 }
}

// ---------- temperature ----------

/// The first day of a sustained temperature rise in the cycle that starts at `from` (and ends before `to`):
/// three days in a row above the highest of the six logged days before them, the third at least [`TEMP_RISE`]
/// above it. Returns (first high day, cover line).
pub fn temp_shift(all: &[Day], from: i64, to: i64) -> Option<(String, i64)> {
    let t: Vec<(i64, i64)> = all.iter().filter(|d| d.temp > 0).map(|d| (n(&d.date), d.temp)).filter(|(x, _)| *x >= from && *x < to).collect();
    for k in 6..t.len().saturating_sub(2) {
        let cover = t[k - 6..k].iter().map(|x| x.1).max()?;
        let (a, b, c) = (t[k], t[k + 1], t[k + 2]);
        if a.1 > cover && b.1 > cover && c.1 >= cover + TEMP_RISE && c.0 - a.0 <= 4 {
            return Some((civil_date(a.0), cover));
        }
    }
    None
}

// ---------- predictions ----------

#[derive(Clone, Debug, PartialEq)]
pub struct Prediction {
    pub start: String,
    pub end: String,
    pub ovulation: String,
    pub fertile_from: String,
    pub fertile_to: String,
    pub pms_from: String,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Outlook {
    pub stats: Stats,
    /// The latest period's start ("" = none logged).
    pub last_start: String,
    /// Day of the cycle today (1 = first day of the period; 0 = no period logged).
    pub cycle_day: i64,
    /// The current cycle's ovulation as the logs show it: a temperature rise (the day before it) or a positive
    /// ovulation test (the day after it). "" = not seen.
    pub ovulation_seen: String,
    pub cover_line: i64,
    /// Upcoming periods, the first being the next one.
    pub next: Vec<Prediction>,
    /// The next period is late: days since its predicted start ([`Outlook::expected`]), counted only once today is
    /// past [`Outlook::late_from`] (UX review U-C5: not from the first day while the prediction is a guess).
    pub late_days: i64,
    /// The predicted start of the next period before a late one is moved to tomorrow ("" = none).
    pub expected: String,
    /// The last day that is still within the usual variation: 5 days after [`Outlook::expected`] while the cycles
    /// aren't learned, else the end of the longest recent cycle (never before the expected day).
    pub late_from: String,
}

fn predict_one(start: i64, luteal: i64, plen: i64) -> Prediction {
    let ov = start - luteal;
    Prediction {
        start: civil_date(start),
        end: civil_date(start + plen - 1),
        ovulation: civil_date(ov),
        fertile_from: civil_date(ov - 5),
        fertile_to: civil_date(ov + 1),
        pms_from: civil_date(start - 5),
    }
}

pub fn outlook(all: &[Day], set: &Settings, today: &str) -> Outlook {
    let st = stats(all, set);
    let p = periods(all);
    let t = n(today);
    let Some(last) = p.iter().rev().find(|x| n(&x.start) <= t) else {
        return Outlook { stats: st, last_start: String::new(), cycle_day: 0, ovulation_seen: String::new(), cover_line: 0, next: Vec::new(), late_days: 0, expected: String::new(), late_from: String::new() };
    };
    let ls = n(&last.start);
    let cycle_day = t - ls + 1;
    // what the logs say about this cycle's ovulation
    let mut ovulation_seen = String::new();
    let mut cover_line = 0;
    if let Some((high, cover)) = temp_shift(all, ls, t + 1) {
        ovulation_seen = civil_date(n(&high) - 1);
        cover_line = cover;
    } else if let Some(d) = all.iter().rev().find(|d| n(&d.date) > ls && n(&d.date) <= t && d.tags.iter().any(|x| x == "tests.ov_pos")) {
        ovulation_seen = civil_date(n(&d.date) + 1);
    }
    // the next start: from a seen ovulation (luteal days after it), else from the average cycle
    let mut first = if ovulation_seen.is_empty() { ls + st.avg_cycle } else { n(&ovulation_seen) + set.luteal };
    let expected = first;
    let late_from = if st.learned { first.max(ls + st.longest) } else { first + LATE_GRACE };
    let late_days = if t > late_from { t - first } else { 0 };
    if first <= t {
        first = t + 1; // a late period is expected any day now; the ones after follow from tomorrow
    }
    let mut next = Vec::new();
    let mut s0 = first;
    while s0 <= t + AHEAD {
        next.push(predict_one(s0, set.luteal, st.avg_period));
        s0 += st.avg_cycle;
    }
    Outlook { stats: st, last_start: last.start.clone(), cycle_day, ovulation_seen, cover_line, next, late_days, expected: civil_date(expected), late_from: civil_date(late_from) }
}

/// What a calendar day shows.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct CalDay {
    pub date: String,
    pub flow: i64,
    pub logged: bool,
    pub period_predicted: bool,
    pub fertile: bool,
    pub ovulation: bool,
    pub pms: bool,
    /// 0 = before the first logged period.
    pub cycle_day: i64,
}

/// Every day from `from` to `to` (inclusive) as the calendar shows it.
pub fn calendar(all: &[Day], set: &Settings, today: &str, from: &str, to: &str) -> Vec<CalDay> {
    let o = outlook(all, set, today);
    let by: BTreeMap<&str, &Day> = all.iter().map(|d| (d.date.as_str(), d)).collect();
    let starts: Vec<i64> = periods(all).iter().map(|p| n(&p.start)).collect();
    let (a, b) = (n(from), n(to).min(n(from) + 400));
    let inr = |x: i64, f: &str, t: &str| x >= n(f) && x <= n(t);
    let t = n(today);
    let mut out = Vec::new();
    for x in a..=b {
        let date = civil_date(x);
        let d = by.get(date.as_str());
        let mut c = CalDay { date: date.clone(), flow: d.map(|d| d.flow).unwrap_or(0), logged: d.is_some(), ..Default::default() };
        // cycle day: from the latest period start on or before this day; for future days, from the predictions
        let past = starts.iter().rev().find(|s| **s <= x).copied();
        let fut = o.next.iter().map(|p| n(&p.start)).rev().find(|s| *s <= x);
        c.cycle_day = match (past, fut) {
            (_, Some(f)) if x > t => x - f + 1,
            (Some(p), _) => x - p + 1,
            _ => 0,
        };
        if x > t || (x == t && c.flow == 0) {
            for p in &o.next {
                c.period_predicted |= inr(x, &p.start, &p.end);
                c.fertile |= inr(x, &p.fertile_from, &p.fertile_to);
                c.ovulation |= x == n(&p.ovulation);
                c.pms |= inr(x, &p.pms_from, &civil_date(n(&p.start) - 1));
            }
        }
        if set.mode == "pregnant" {
            c.period_predicted = false;
            c.fertile = false;
            c.ovulation = false;
            c.pms = false;
        }
        out.push(c);
    }
    out
}

// ---------- patterns ----------

#[derive(Clone, Debug, PartialEq)]
pub struct Pattern {
    pub tag: String,
    /// In how many of the finished cycles looked at it came up, and how many were looked at.
    pub cycles_with: i64,
    pub cycles: i64,
    /// Counted from the period start (true: cycle days `from`..`to`) or back from the next period (false:
    /// `from`..`to` days before it).
    pub from_start: bool,
    pub from: i64,
    pub to: i64,
}

/// Tags that come back at the same point of the cycle: in at least half of the last cycles (and at least 2),
/// within a span of at most 7 days, counted either from the period start or back from the next one.
pub fn patterns(all: &[Day]) -> Vec<Pattern> {
    let p = periods(all);
    if p.len() < 3 {
        return Vec::new();
    }
    let cycles: Vec<(i64, i64)> = p.windows(2).map(|w| (n(&w[0].start), n(&w[1].start))).filter(|(a, b)| (CYCLE_MIN..=CYCLE_MAX).contains(&(b - a))).collect();
    let cycles: Vec<(i64, i64)> = cycles.into_iter().rev().take(12).collect();
    let total = cycles.len() as i64;
    let mut seen: BTreeMap<&str, Vec<(i64, i64)>> = BTreeMap::new(); // tag -> (day from start, days before next)
    let mut in_cycles: BTreeMap<&str, BTreeSet<i64>> = BTreeMap::new();
    for d in all {
        let x = n(&d.date);
        if let Some((a, b)) = cycles.iter().find(|(a, b)| x >= *a && x < *b) {
            for tag in &d.tags {
                if tag.starts_with("tests.") || tag.starts_with("sex.") || tag.starts_with("meds.") {
                    continue;
                }
                seen.entry(tag.as_str()).or_default().push((x - a + 1, b - x));
                in_cycles.entry(tag.as_str()).or_default().insert(*a);
            }
        }
    }
    let span = |v: &mut Vec<i64>| -> (i64, i64) {
        v.sort();
        // the middle 80% of the days it came up on
        let lo = v[(v.len() as f64 * 0.1) as usize];
        let hi = v[((v.len() as f64 * 0.9).ceil() as usize).min(v.len()) - 1];
        (lo, hi)
    };
    let mut out = Vec::new();
    for (tag, xs) in seen {
        let with = in_cycles[tag].len() as i64;
        if with < 2 || with * 2 < total {
            continue;
        }
        let (mut a, mut b): (Vec<i64>, Vec<i64>) = xs.iter().copied().unzip();
        let (fa, ta) = span(&mut a);
        let (fb, tb) = span(&mut b);
        // the tighter way of counting; on a tie, early days count from the start and late ones back from the period
        let (from_start, from, to) = if ta - fa < tb - fb || (ta - fa == tb - fb && fa <= 10) { (true, fa, ta) } else { (false, fb, tb) };
        let (from, to) = if from_start { (from, to) } else { (from.min(to), from.max(to)) };
        if to - from <= 6 {
            out.push(Pattern { tag: tag.to_string(), cycles_with: with, cycles: total, from_start, from, to });
        }
    }
    out.sort_by(|x, y| (y.cycles_with * 100 / y.cycles).cmp(&(x.cycles_with * 100 / x.cycles)).then(x.tag.cmp(&y.tag)));
    out
}

// ---------- pregnancy and birth control ----------

/// Pregnancy from the first day of the last period: (week, day of that week, due date). None outside 0-44 weeks.
pub fn pregnancy(lmp: &str, today: &str) -> Option<(i64, i64, String)> {
    if !valid_date(lmp) || !valid_date(today) {
        return None;
    }
    let days = n(today) - n(lmp);
    if !(0..=44 * 7).contains(&days) {
        return None;
    }
    Some((days / 7, days % 7, civil_date(n(lmp) + 280)))
}

/// Where a pill pack is today: (day of the pack from 1, an active day or a break day). None without a pack start.
pub fn pill_day(set: &Settings, today: &str) -> Option<(i64, bool)> {
    if set.bc != "pill" || !valid_date(&set.bc_start) {
        return None;
    }
    let len = set.bc_active + set.bc_break;
    let d = n(today) - n(&set.bc_start);
    if d < 0 {
        return None;
    }
    let k = d.rem_euclid(len) + 1;
    Some((k, k <= set.bc_active))
}

/// The next day a ring, patch or shot is changed, from the day it started: ring 21 days in then 7 out, patch every
/// 7 days (3 weeks, then a week off), shot every 13 weeks. None for the others.
pub fn next_change(set: &Settings, today: &str) -> Option<String> {
    if !valid_date(&set.bc_start) {
        return None;
    }
    let (s0, t) = (n(&set.bc_start), n(today));
    let steps: &[i64] = match set.bc.as_str() {
        "ring" => &[21, 7],
        "patch" => &[7, 7, 7, 7],
        "shot" => &[91],
        _ => return None,
    };
    let mut x = s0;
    let mut k = 0;
    while x <= t {
        x += steps[k % steps.len()];
        k += 1;
        if k > 100_000 {
            return None;
        }
    }
    Some(civil_date(x))
}

// ---------- the partner's summary ----------
//
// A partner never joins the journal. The journal's owner makes a separate small document (`pview` root map) and
// shares that one; the owner's app writes into it only what the owner chose to share (`cset.psh`, kept in the
// journal, where the partner can't change it). The partner's app shows it read-only.

/// What can be shared with a partner: the next period and cycle day, the fertile window, today's feelings and
/// energy, the pregnancy week.
pub const SHARES: [&str; 4] = ["period", "fertile", "feel", "preg"];
const VIEW_KEYS: [&str; 15] = ["name", "at", "cday", "next", "late", "exp", "latefrom", "ff", "ft", "ov", "feel", "pweek", "due", "mode", "src"];

pub fn shared_items(doc: &LoroDoc) -> Vec<String> {
    s(&doc.get_map("cset"), "psh").split(',').filter(|x| SHARES.contains(x)).map(String::from).collect()
}

pub fn set_shared_items(doc: &LoroDoc, items: &[String]) -> Result<()> {
    if !items.iter().all(|x| SHARES.contains(&x.as_str())) {
        return Err(Error::Invalid("unknown share".into()));
    }
    doc.get_map("cset").insert("psh", items.join(",")).map_err(|e| Error::Invalid(e.to_string()))
}

/// The fields the partner sees, from the journal: only the chosen ones (and the owner's name and the date).
pub fn view_fields(all: &[Day], set: &Settings, shares: &[String], today: &str, name: &str) -> BTreeMap<String, String> {
    let has = |k: &str| shares.iter().any(|x| x == k);
    let o = outlook(all, set, today);
    let mut v = BTreeMap::new();
    v.insert("name".into(), clip(name, MAX_NAME));
    v.insert("at".into(), today.to_string());
    v.insert("mode".into(), if set.mode == "pregnant" && has("preg") { "pregnant".into() } else { String::new() });
    if set.mode != "pregnant" {
        if has("period") && !o.last_start.is_empty() {
            v.insert("cday".into(), o.cycle_day.to_string());
            if let Some(n) = o.next.first() {
                v.insert("next".into(), n.start.clone());
            }
            v.insert("late".into(), o.late_days.to_string());
            // the partner's phone counts on from these when this one hasn't written a newer summary (U-C2, U-C5)
            v.insert("exp".into(), o.expected.clone());
            v.insert("latefrom".into(), o.late_from.clone());
        }
        if has("fertile") {
            if let Some(n) = o.next.first() {
                v.insert("ff".into(), n.fertile_from.clone());
                v.insert("ft".into(), n.fertile_to.clone());
                v.insert("ov".into(), n.ovulation.clone());
            }
        }
    } else if has("preg") {
        if let Some((w, _, due)) = pregnancy(&set.lmp, today) {
            v.insert("pweek".into(), w.to_string());
            v.insert("due".into(), due);
        }
    }
    if has("feel") {
        let t: Vec<&str> = all.iter().find(|d| d.date == today).map(|d| d.tags.iter().filter(|t| t.starts_with("feel.") || t.starts_with("energy.")).map(|t| t.as_str()).collect()).unwrap_or_default();
        v.insert("feel".into(), t.join(","));
    }
    v
}

pub fn is_view(doc: &LoroDoc) -> bool {
    !s(&doc.get_map("pview"), "src").is_empty()
}

/// Mark a new document as the partner summary of journal `src`.
pub fn init_view(doc: &LoroDoc, src: &str) -> Result<()> {
    if src.is_empty() || src.len() > 64 {
        return Err(Error::Invalid("bad journal id".into()));
    }
    doc.get_map("pview").insert("src", src).map_err(|e| Error::Invalid(e.to_string()))
}

pub fn view_src(doc: &LoroDoc) -> String {
    s(&doc.get_map("pview"), "src")
}

/// Write the summary; fields that are not in `fields` are taken out. Returns whether anything changed (nothing
/// is written when it is the same, so a summary costs nothing to sync on days nothing changes).
pub fn write_view(doc: &LoroDoc, fields: &BTreeMap<String, String>) -> Result<bool> {
    let m = doc.get_map("pview");
    let mut changed = false;
    for k in VIEW_KEYS {
        if k == "src" {
            continue;
        }
        let cur = s(&m, k);
        match fields.get(k) {
            Some(v) if *v != cur => {
                m.insert(k, v.as_str()).map_err(|e| Error::Invalid(e.to_string()))?;
                changed = true;
            }
            None if m.get(k).is_some() => {
                m.delete(k).map_err(|e| Error::Invalid(e.to_string()))?;
                changed = true;
            }
            _ => {}
        }
    }
    Ok(changed)
}

/// The summary as the partner's app shows it, every value checked (the other side's app may have written anything).
pub fn read_view(doc: &LoroDoc) -> BTreeMap<String, String> {
    let m = doc.get_map("pview");
    let mut out = BTreeMap::new();
    for k in VIEW_KEYS {
        let v = s(&m, k);
        let ok = match k {
            "name" => v.chars().count() <= MAX_NAME,
            "at" | "next" | "exp" | "latefrom" | "ff" | "ft" | "ov" | "due" => valid_date(&v),
            "cday" | "late" | "pweek" => v.parse::<i64>().is_ok_and(|x| (0..=400).contains(&x)),
            "feel" => v.split(',').all(|t| builtin_tag(t) && (t.starts_with("feel.") || t.starts_with("energy."))),
            "mode" => v == "pregnant",
            _ => false,
        };
        if ok && !v.is_empty() {
            out.insert(k.to_string(), shown(v, MAX_NAME * 4));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_partner_sees_only_what_was_chosen() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 5);
        set_tag(&d, "2026-02-05", "feel.happy", true).unwrap();
        set_tag(&d, "2026-02-05", "pain.cramps", true).unwrap();
        set_note(&d, "2026-02-05", "private").unwrap();
        let (all, set) = (days(&d), settings(&d));
        let none = view_fields(&all, &set, &[], "2026-02-05", "Ana");
        assert_eq!(none.keys().map(|k| k.as_str()).collect::<Vec<_>>(), ["at", "mode", "name"]);
        set_shared_items(&d, &["period".into(), "feel".into()]).unwrap();
        let v = view_fields(&all, &set, &shared_items(&d), "2026-02-05", "Ana");
        assert_eq!(v["cday"], "8");
        assert_eq!(v["next"], "2026-02-26");
        assert_eq!((v["exp"].as_str(), v["latefrom"].as_str()), ("2026-02-26", "2026-03-03"), "the partner's phone counts late from these");
        assert_eq!(v["feel"], "feel.happy", "feelings only, never pain or notes");
        assert!(!v.contains_key("ff"));
        let pv = doc();
        init_view(&pv, "abc").unwrap();
        assert!(write_view(&pv, &v).unwrap());
        assert!(!write_view(&pv, &v).unwrap(), "the same summary writes nothing");
        assert_eq!(read_view(&pv)["next"], "2026-02-26");
        assert_eq!(read_view(&pv)["latefrom"], "2026-03-03");
        // the partner's side writing something odd is not shown
        pv.get_map("pview").insert("feel", "pain.cramps").unwrap();
        pv.get_map("pview").insert("next", "soon").unwrap();
        let r = read_view(&pv);
        assert!(!r.contains_key("feel") && !r.contains_key("next"));
        assert!(set_shared_items(&d, &["notes".into()]).is_err());
    }

    fn doc() -> LoroDoc {
        LoroDoc::new()
    }

    fn period(d: &LoroDoc, start: &str, len: i64) {
        log_period(d, start, len).unwrap();
    }

    #[test]
    fn periods_and_lengths_come_from_flow_days() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 4);
        period(&d, "2026-02-27", 5);
        set_flow(&d, "2026-02-15", 1).unwrap(); // spotting starts nothing
        let all = days(&d);
        let p = periods(&all);
        assert_eq!(p.iter().map(|x| x.start.as_str()).collect::<Vec<_>>(), ["2026-01-01", "2026-01-29", "2026-02-27"]);
        let st = stats(&all, &settings(&d));
        assert_eq!(st.cycles.iter().map(|c| c.1).collect::<Vec<_>>(), [28, 29]);
        assert_eq!(st.avg_cycle, 29); // 28.5 rounds up
        assert_eq!(st.avg_period, 5);
        assert!(st.learned && !st.irregular);
    }

    #[test]
    fn a_day_off_in_the_middle_is_one_period() {
        let d = doc();
        set_flow(&d, "2026-03-01", 3).unwrap();
        set_flow(&d, "2026-03-03", 2).unwrap();
        set_flow(&d, "2026-03-04", 2).unwrap();
        assert_eq!(periods(&days(&d)).len(), 1);
        set_flow(&d, "2026-03-08", 3).unwrap(); // 4 days later: a new one
        assert_eq!(periods(&days(&d)).len(), 2);
    }

    #[test]
    fn predictions_and_fertile_window() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 5);
        period(&d, "2026-02-26", 5);
        let o = outlook(&days(&d), &settings(&d), "2026-03-05");
        assert_eq!(o.cycle_day, 8);
        let p = &o.next[0];
        assert_eq!(p.start, "2026-03-26"); // 28 days after Feb 26
        assert_eq!(p.ovulation, "2026-03-12"); // 14 days before
        assert_eq!((p.fertile_from.as_str(), p.fertile_to.as_str()), ("2026-03-07", "2026-03-13"));
        assert!(o.next.len() >= 13, "a year ahead");
    }

    #[test]
    fn one_period_uses_the_settings() {
        let d = doc();
        set_setting(&d, "clen", "32").unwrap();
        period(&d, "2026-01-01", 5);
        let o = outlook(&days(&d), &settings(&d), "2026-01-10");
        assert_eq!(o.next[0].start, "2026-02-02");
        assert!(!o.stats.learned);
    }

    #[test]
    fn a_late_period_is_expected_from_tomorrow() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 5);
        let o = outlook(&days(&d), &settings(&d), "2026-03-01"); // predicted Feb 26
        assert_eq!(o.late_days, 0, "one cycle is a guess: not late within {LATE_GRACE} days");
        assert_eq!((o.expected.as_str(), o.late_from.as_str()), ("2026-02-26", "2026-03-03"));
        assert_eq!(o.next[0].start, "2026-03-02");
        let o = outlook(&days(&d), &settings(&d), "2026-03-04");
        assert_eq!(o.late_days, 6, "counted from the predicted day once past the grace");
    }

    #[test]
    fn a_learned_period_is_late_after_the_longest_cycle() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 5); // 28
        period(&d, "2026-03-02", 5); // 32: average 30, longest 32
        let o = outlook(&days(&d), &settings(&d), "2026-04-02"); // predicted Apr 1, longest ends Apr 3
        assert!(o.stats.learned);
        assert_eq!((o.late_days, o.late_from.as_str()), (0, "2026-04-03"));
        assert_eq!(outlook(&days(&d), &settings(&d), "2026-04-04").late_days, 3);
    }

    #[test]
    fn a_temperature_rise_moves_the_next_period() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 5);
        let lows = [3640, 3650, 3630, 3645, 3640, 3635];
        for (k, t) in lows.iter().enumerate() {
            set_temp(&d, &civil_date(n("2026-02-10") + k as i64), *t).unwrap();
        }
        for (k, t) in [3670, 3680, 3690].iter().enumerate() {
            set_temp(&d, &civil_date(n("2026-02-16") + k as i64), *t).unwrap();
        }
        let all = days(&d);
        assert_eq!(temp_shift(&all, n("2026-01-29"), n("2026-02-20")), Some(("2026-02-16".into(), 3650)));
        let o = outlook(&all, &settings(&d), "2026-02-19");
        assert_eq!(o.ovulation_seen, "2026-02-15");
        assert_eq!(o.next[0].start, "2026-03-01"); // 14 days after ovulation, not 28 after the start
    }

    #[test]
    fn a_small_wobble_is_not_a_rise() {
        let d = doc();
        for (k, t) in [3640, 3650, 3630, 3645, 3640, 3635, 3655, 3660, 3665].iter().enumerate() {
            set_temp(&d, &civil_date(n("2026-02-10") + k as i64), *t).unwrap();
        }
        assert_eq!(temp_shift(&days(&d), n("2026-02-01"), n("2026-03-01")), None);
    }

    #[test]
    fn calendar_marks_predictions_only_ahead() {
        let d = doc();
        period(&d, "2026-01-01", 5);
        period(&d, "2026-01-29", 5);
        let cal = calendar(&days(&d), &settings(&d), "2026-02-05", "2026-02-01", "2026-03-31");
        let at = |x: &str| cal.iter().find(|c| c.date == x).unwrap().clone();
        assert_eq!(at("2026-02-02").flow, 2); // the last day of a logged period is light
        assert_eq!(at("2026-02-02").cycle_day, 5);
        assert!(at("2026-02-26").period_predicted);
        assert!(at("2026-02-12").ovulation);
        assert!(at("2026-02-10").fertile);
        assert!(!at("2026-02-03").fertile, "the past shows what was logged");
        set_setting(&d, "mode", "pregnant").unwrap();
        let cal = calendar(&days(&d), &settings(&d), "2026-02-05", "2026-02-01", "2026-03-31");
        assert!(cal.iter().all(|c| !c.period_predicted && !c.fertile));
    }

    #[test]
    fn patterns_find_what_comes_back() {
        let d = doc();
        let starts = ["2026-01-01", "2026-01-29", "2026-02-26", "2026-03-26", "2026-04-23"];
        for s0 in starts {
            period(&d, s0, 5);
            set_tag(&d, s0, "pain.cramps", true).unwrap();
            set_tag(&d, &civil_date(n(s0) + 1), "pain.cramps", true).unwrap();
            set_tag(&d, &civil_date(n(s0) - 2), "skin.acne", true).unwrap();
        }
        set_tag(&d, "2026-02-10", "feel.happy", true).unwrap();
        let p = patterns(&days(&d));
        let cr = p.iter().find(|x| x.tag == "pain.cramps").unwrap();
        assert!(cr.from_start && cr.from == 1 && cr.to == 2 && cr.cycles_with == 4 && cr.cycles == 4);
        let ac = p.iter().find(|x| x.tag == "skin.acne").unwrap();
        assert!(!ac.from_start && ac.from == 2 && ac.to == 2);
        assert!(p.iter().all(|x| x.tag != "feel.happy"), "once is not a pattern");
    }

    #[test]
    fn values_are_checked_both_ways() {
        let d = doc();
        assert!(set_flow(&d, "2026-02-30", 2).is_err());
        assert!(set_flow(&d, "2026-02-03", 9).is_err());
        assert!(set_tag(&d, "2026-02-03", "pain.nonsense", true).is_err());
        assert!(set_temp(&d, "2026-02-03", 5000).is_err());
        assert!(set_weight(&d, "2026-02-03", 10).is_err());
        assert!(set_setting(&d, "clen", "300").is_err());
        assert!(set_setting(&d, "mode", "other").is_err());
        // what another device wrote is read with the same rules
        let m = d.get_map("cday").ensure_mergeable_map("2026-02-04").unwrap();
        m.insert("flow", 77i64).unwrap();
        m.insert("temp", 99_999i64).unwrap();
        m.insert("note", "a\u{0007}b").unwrap();
        let t = m.ensure_mergeable_map("tags").unwrap();
        t.insert("__proto__", 1i64).unwrap();
        t.insert("pain.cramps", 1i64).unwrap();
        let x = day(&d, "2026-02-04");
        assert_eq!((x.flow, x.temp, x.note.as_str()), (0, 0, "ab"));
        assert_eq!(x.tags, ["pain.cramps"]);
    }

    #[test]
    fn custom_tags_work_like_built_in_ones() {
        let d = doc();
        let id = add_custom_tag(&d, "  Walked the dog ", "move").unwrap();
        set_tag(&d, "2026-02-03", &id, true).unwrap();
        assert_eq!(day(&d, "2026-02-03").tags, [id.clone()]);
        delete_custom_tag(&d, &id, 1).unwrap();
        assert!(day(&d, "2026-02-03").tags.is_empty(), "a deleted tag is no longer shown");
        assert!(add_custom_tag(&d, "x", "nope").is_err());
    }

    #[test]
    fn two_devices_logging_the_same_day_apart_keep_both() {
        // pinned: day and tag maps are mergeable, so neither device's log of the day replaces the other's
        let a = doc();
        a.set_peer_id(1).unwrap();
        let b = doc();
        b.set_peer_id(2).unwrap();
        set_flow(&a, "2026-03-04", 3).unwrap();
        set_tag(&a, "2026-03-04", "pain.cramps", true).unwrap();
        set_note(&b, "2026-03-04", "long walk").unwrap();
        set_tag(&b, "2026-03-04", "feel.calm", true).unwrap();
        a.import(&b.export(loro::ExportMode::all_updates()).unwrap()).unwrap();
        b.import(&a.export(loro::ExportMode::all_updates()).unwrap()).unwrap();
        for d in [&a, &b] {
            let x = day(d, "2026-03-04");
            assert_eq!(x.flow, 3);
            assert_eq!(x.note, "long walk");
            let mut tags = x.tags.clone();
            tags.sort();
            assert_eq!(tags, ["feel.calm".to_string(), "pain.cramps".to_string()]); // pinned: built-in tag ids
        }
    }

    #[test]
    fn pregnancy_and_birth_control() {
        assert_eq!(pregnancy("2026-01-01", "2026-03-05"), Some((9, 0, "2026-10-08".into())));
        assert_eq!(pregnancy("2026-01-01", "2025-12-01"), None);
        let d = doc();
        set_setting(&d, "bc", "pill").unwrap();
        set_setting(&d, "bcs", "2026-02-01").unwrap();
        let st = settings(&d);
        assert_eq!(pill_day(&st, "2026-02-01"), Some((1, true)));
        assert_eq!(pill_day(&st, "2026-02-22"), Some((22, false)));
        assert_eq!(pill_day(&st, "2026-03-01"), Some((1, true)));
        set_setting(&d, "bc", "ring").unwrap();
        assert_eq!(next_change(&settings(&d), "2026-02-05"), Some("2026-02-22".into()));
        assert_eq!(next_change(&settings(&d), "2026-02-22"), Some("2026-03-01".into()));
    }
}
