//! Ciclo: the cycle calls (kt_tasks::cycle over a list document, the journal). Dates are "YYYY-MM-DD" in the
//! person's own calendar (the app passes `today`); temperatures are hundredths of a degree Celsius, weights grams.

use std::time::{SystemTime, UNIX_EPOCH};

use kt_tasks::cycle as c;

use crate::{fail, KtError, KtNode};

#[derive(Clone, Debug, uniffi::Record)]
pub struct CycleSettings {
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

#[derive(Clone, Debug, uniffi::Record)]
pub struct CycleDay {
    pub date: String,
    pub flow: i64,
    pub tags: Vec<String>,
    pub temp: i64,
    pub weight: i64,
    pub note: String,
    pub bc_taken: bool,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CalItem {
    pub date: String,
    pub flow: i64,
    pub logged: bool,
    pub period_predicted: bool,
    pub fertile: bool,
    pub ovulation: bool,
    pub pms: bool,
    pub cycle_day: i64,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PredictionItem {
    pub start: String,
    pub end: String,
    pub ovulation: String,
    pub fertile_from: String,
    pub fertile_to: String,
    pub pms_from: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CycleLen {
    pub start: String,
    pub days: i64,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PeriodItem {
    pub start: String,
    pub end: String,
    pub days: i64,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct OutlookView {
    pub cycles: Vec<CycleLen>,
    pub periods: Vec<PeriodItem>,
    pub avg_cycle: i64,
    pub avg_period: i64,
    pub shortest: i64,
    pub longest: i64,
    pub learned: bool,
    pub irregular: bool,
    pub last_start: String,
    pub cycle_day: i64,
    pub ovulation_seen: String,
    pub cover_line: i64,
    pub next: Vec<PredictionItem>,
    pub late_days: i64,
    /// Pregnancy mode: week and day, and the due date (week -1 = no date set).
    pub preg_week: i64,
    pub preg_day: i64,
    pub due: String,
    /// Pill: day of the pack (0 = no pack) and whether it is an active day.
    pub pill_day: i64,
    pub pill_active: bool,
    /// Ring, patch or shot: the next change ("" = none).
    pub next_change: String,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PatternItem {
    pub tag: String,
    pub cycles_with: i64,
    pub cycles: i64,
    pub from_start: bool,
    pub from: i64,
    pub to: i64,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct CustomTagItem {
    pub id: String,
    pub name: String,
    pub cat: String,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn day_item(d: c::Day) -> CycleDay {
    CycleDay { date: d.date, flow: d.flow, tags: d.tags, temp: d.temp, weight: d.weight, note: d.note, bc_taken: d.bc_taken }
}

#[uniffi::export]
impl KtNode {
    pub fn cycle_settings(&self, list_id: String) -> Result<CycleSettings, KtError> {
        let l = self.list(&list_id)?;
        let s = self.app.read(&l, c::settings).map_err(fail)?;
        Ok(CycleSettings {
            mode: s.mode,
            cycle_len: s.cycle_len,
            period_len: s.period_len,
            luteal: s.luteal,
            lmp: s.lmp,
            bc: s.bc,
            bc_start: s.bc_start,
            bc_active: s.bc_active,
            bc_break: s.bc_break,
            hidden: s.hidden,
        })
    }

    pub fn cycle_set_setting(&self, list_id: String, key: String, value: String) -> Result<(), KtError> {
        let l = self.list(&list_id)?;
        self.app.edit(&l, |d| c::set_setting(d, &key, &value)).map_err(fail)?;
        self.notify();
        Ok(())
    }

    pub fn cycle_days(&self, list_id: String) -> Result<Vec<CycleDay>, KtError> {
        let l = self.list(&list_id)?;
        Ok(self.app.read(&l, c::days).map_err(fail)?.into_iter().map(day_item).collect())
    }

    pub fn cycle_day(&self, list_id: String, date: String) -> Result<CycleDay, KtError> {
        let l = self.list(&list_id)?;
        Ok(day_item(self.app.read(&l, |d| c::day(d, &date)).map_err(fail)?))
    }

    pub fn cycle_outlook(&self, list_id: String, today: String) -> Result<OutlookView, KtError> {
        let l = self.list(&list_id)?;
        let (all, set) = self.app.read(&l, |d| (c::days(d), c::settings(d))).map_err(fail)?;
        let o = c::outlook(&all, &set, &today);
        let preg = c::pregnancy(&set.lmp, &today);
        let pill = c::pill_day(&set, &today);
        Ok(OutlookView {
            cycles: o.stats.cycles.iter().map(|(s, d)| CycleLen { start: s.clone(), days: *d }).collect(),
            periods: c::periods(&all).into_iter().map(|p| PeriodItem { start: p.start, end: p.end, days: p.days }).collect(),
            avg_cycle: o.stats.avg_cycle,
            avg_period: o.stats.avg_period,
            shortest: o.stats.shortest,
            longest: o.stats.longest,
            learned: o.stats.learned,
            irregular: o.stats.irregular,
            last_start: o.last_start,
            cycle_day: o.cycle_day,
            ovulation_seen: o.ovulation_seen,
            cover_line: o.cover_line,
            next: o
                .next
                .into_iter()
                .map(|p| PredictionItem { start: p.start, end: p.end, ovulation: p.ovulation, fertile_from: p.fertile_from, fertile_to: p.fertile_to, pms_from: p.pms_from })
                .collect(),
            late_days: o.late_days,
            preg_week: preg.as_ref().map(|p| p.0).unwrap_or(-1),
            preg_day: preg.as_ref().map(|p| p.1).unwrap_or(0),
            due: preg.map(|p| p.2).unwrap_or_default(),
            pill_day: pill.map(|p| p.0).unwrap_or(0),
            pill_active: pill.map(|p| p.1).unwrap_or(false),
            next_change: c::next_change(&set, &today).unwrap_or_default(),
        })
    }

    pub fn cycle_calendar(&self, list_id: String, today: String, from: String, to: String) -> Result<Vec<CalItem>, KtError> {
        let l = self.list(&list_id)?;
        let (all, set) = self.app.read(&l, |d| (c::days(d), c::settings(d))).map_err(fail)?;
        Ok(c::calendar(&all, &set, &today, &from, &to)
            .into_iter()
            .map(|x| CalItem {
                date: x.date,
                flow: x.flow,
                logged: x.logged,
                period_predicted: x.period_predicted,
                fertile: x.fertile,
                ovulation: x.ovulation,
                pms: x.pms,
                cycle_day: x.cycle_day,
            })
            .collect())
    }

    pub fn cycle_patterns(&self, list_id: String) -> Result<Vec<PatternItem>, KtError> {
        let l = self.list(&list_id)?;
        let all = self.app.read(&l, c::days).map_err(fail)?;
        Ok(c::patterns(&all)
            .into_iter()
            .map(|p| PatternItem { tag: p.tag, cycles_with: p.cycles_with, cycles: p.cycles, from_start: p.from_start, from: p.from, to: p.to })
            .collect())
    }

    pub fn cycle_set_flow(&self, list_id: String, date: String, flow: i64) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::set_flow(d, &date, flow))
    }

    pub fn cycle_log_period(&self, list_id: String, start: String, days: i64) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::log_period(d, &start, days))
    }

    pub fn cycle_set_tag(&self, list_id: String, date: String, tag: String, on: bool) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::set_tag(d, &date, &tag, on))
    }

    pub fn cycle_set_temp(&self, list_id: String, date: String, temp: i64) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::set_temp(d, &date, temp))
    }

    pub fn cycle_set_weight(&self, list_id: String, date: String, grams: i64) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::set_weight(d, &date, grams))
    }

    pub fn cycle_set_note(&self, list_id: String, date: String, note: String) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::set_note(d, &date, &note))
    }

    pub fn cycle_set_bc_taken(&self, list_id: String, date: String, taken: bool) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::set_bc_taken(d, &date, taken))
    }

    /// Every logged day as CSV (one row a day; tags by their names: `labels` holds the app's words for the built-in
    /// ones, custom tags by the name she gave them, else the id). No cell can start a formula.
    pub fn export_csv(&self, list_id: String, labels: std::collections::HashMap<String, String>) -> Result<String, KtError> {
        let l = self.list(&list_id)?;
        let (all, custom) = self.app.read(&l, |d| (c::days(d), c::custom_tags(d))).map_err(fail)?;
        let names: std::collections::BTreeMap<String, String> = custom.into_iter().map(|t| (t.id, t.name)).collect();
        let q = crate::budget::csv_cell;
        let flow = ["", "spotting", "light", "medium", "heavy"];
        let mut out = String::from("date,flow,tags,temperature_c,weight_kg,birth_control_taken,note\n");
        for d in all {
            let tags: Vec<String> = d.tags.iter().map(|t| names.get(t).or_else(|| labels.get(t)).cloned().unwrap_or_else(|| t.clone())).collect();
            let temp = if d.temp > 0 { format!("{}.{:02}", d.temp / 100, d.temp % 100) } else { String::new() };
            let wt = if d.weight > 0 { format!("{}.{:03}", d.weight / 1000, d.weight % 1000) } else { String::new() };
            out.push_str(&format!(
                "{},{},{},{},{},{},{}\n",
                q(&d.date),
                q(flow[d.flow.clamp(0, 4) as usize]),
                q(&tags.join("; ")),
                q(&temp),
                q(&wt),
                q(if d.bc_taken { "yes" } else { "" }),
                q(&d.note.replace('\n', " ")),
            ));
        }
        Ok(out)
    }

    pub fn cycle_custom_tags(&self, list_id: String) -> Result<Vec<CustomTagItem>, KtError> {
        let l = self.list(&list_id)?;
        Ok(self.app.read(&l, c::custom_tags).map_err(fail)?.into_iter().map(|t| CustomTagItem { id: t.id, name: t.name, cat: t.cat }).collect())
    }

    pub fn cycle_add_custom_tag(&self, list_id: String, name: String, cat: String) -> Result<String, KtError> {
        let l = self.list(&list_id)?;
        let id = self.app.edit(&l, |d| c::add_custom_tag(d, &name, &cat)).map_err(fail)?;
        self.notify();
        Ok(id)
    }

    pub fn cycle_delete_custom_tag(&self, list_id: String, tag_id: String) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::delete_custom_tag(d, &tag_id, now_ms()))
    }
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct PartnerSummary {
    pub name: String,
    /// The day the summary was written.
    pub at: String,
    pub cycle_day: i64,
    pub next_period: String,
    pub late_days: i64,
    /// The predicted start before a late period moved it, and the last day that is still usual ("" in old summaries).
    pub expected: String,
    pub late_from: String,
    pub fertile_from: String,
    pub fertile_to: String,
    pub ovulation: String,
    pub feel: Vec<String>,
    pub pregnant: bool,
    pub preg_week: i64,
    pub due: String,
}

#[uniffi::export]
impl KtNode {
    /// Whether a list is a partner's summary (not a journal).
    pub fn partner_is_view(&self, list_id: String) -> Result<bool, KtError> {
        let l = self.list(&list_id)?;
        self.app.read(&l, c::is_view).map_err(fail)
    }

    /// Make `list_id` (a new, empty list) the partner summary of the journal `journal_id`.
    pub fn partner_init(&self, list_id: String, journal_id: String) -> Result<(), KtError> {
        self.cycle_edit(&list_id, |d| c::init_view(d, &journal_id))?;
        self.partner_publish(journal_id, String::new())
    }

    /// The summaries this journal writes (its partners' lists).
    pub fn partner_lists(&self, journal_id: String) -> Vec<crate::ListItem> {
        self.lists().into_iter().filter(|l| self.list(&l.id).ok().and_then(|x| self.app.read(&x, c::view_src).ok()).is_some_and(|s| s == journal_id)).collect()
    }

    pub fn partner_shares(&self, journal_id: String) -> Result<Vec<String>, KtError> {
        let l = self.list(&journal_id)?;
        self.app.read(&l, c::shared_items).map_err(fail)
    }

    pub fn partner_set_shares(&self, journal_id: String, items: Vec<String>) -> Result<(), KtError> {
        self.cycle_edit(&journal_id, |d| c::set_shared_items(d, &items))?;
        self.partner_publish(journal_id, String::new())
    }

    /// Bring every partner summary of this journal up to date (writes only what changed). `today` "" = the
    /// device's own date in UTC.
    pub fn partner_publish(&self, journal_id: String, today: String) -> Result<(), KtError> {
        let today = if today.is_empty() { kt_tasks::budget::day_of_ms(now_ms()) } else { today };
        let j = self.list(&journal_id)?;
        let name = self.app.display_name();
        let fields = self.app.read(&j, |d| c::view_fields(&c::days(d), &c::settings(d), &c::shared_items(d), &today, &name)).map_err(fail)?;
        let mut any = false;
        for p in self.partner_lists(journal_id) {
            let l = self.list(&p.id)?;
            any |= self.app.edit(&l, |d| c::write_view(d, &fields)).map_err(fail)?;
        }
        if any {
            self.notify();
        }
        Ok(())
    }

    pub fn partner_view(&self, list_id: String) -> Result<PartnerSummary, KtError> {
        let l = self.list(&list_id)?;
        let v = self.app.read(&l, c::read_view).map_err(fail)?;
        let g = |k: &str| v.get(k).cloned().unwrap_or_default();
        let n = |k: &str| v.get(k).and_then(|x| x.parse().ok()).unwrap_or(-1);
        Ok(PartnerSummary {
            name: g("name"),
            at: g("at"),
            cycle_day: n("cday"),
            next_period: g("next"),
            late_days: n("late").max(0),
            expected: g("exp"),
            late_from: g("latefrom"),
            fertile_from: g("ff"),
            fertile_to: g("ft"),
            ovulation: g("ov"),
            feel: v.get("feel").map(|f| f.split(',').map(String::from).collect()).unwrap_or_default(),
            pregnant: g("mode") == "pregnant",
            preg_week: n("pweek"),
            due: g("due"),
        })
    }
}

impl KtNode {
    fn cycle_edit(&self, list_id: &str, f: impl FnOnce(&loro::LoroDoc) -> kt_tasks::Result<()>) -> Result<(), KtError> {
        let l = self.list(list_id)?;
        self.app.edit(&l, f).map_err(fail)?;
        self.notify();
        Ok(())
    }
}
