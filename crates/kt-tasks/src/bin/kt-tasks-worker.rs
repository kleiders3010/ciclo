//! Import worker for the tasks app (D3). Started by the host with `--kt-import-worker`.
fn main() {
    // the app's own readers run on the merged document too (T2-3)
    kt_engine::sandbox::worker_main_with(|d| {
        let _ = kt_tasks::tasks(d);
        let _ = kt_tasks::normalized(d);
        // and the cycle journal's (second-pass Sobres audit S3-9 b, the same for Ciclo)
        let _ = kt_tasks::cycle::settings(d);
        let _ = kt_tasks::cycle::days(d);
        let _ = kt_tasks::cycle::custom_tags(d);
        let _ = kt_tasks::cycle::read_view(d);
    });
}
