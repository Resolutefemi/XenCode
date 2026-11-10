//! Timer registry — `setTimeout` / `setInterval` driven by the runtime's
//! async schedular.
//!
//! `setTimeout` / `setInterval` spawn a future on the QuickJS runtime
//! ([`Ctx::spawn`]) which sleeps via tokio and then calls the callback while
//! the runtime lock is held. Cancellation uses a plain-Rust flag registry so
//! `clearTimeout` / `clearInterval` need no JS values and are always safe.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use rquickjs::function::Rest;
use rquickjs::{AsyncContext, Ctx, Function, Result, Value};
#[cfg(test)]
use rquickjs::AsyncRuntime;

struct Registry {
    next: u32,
    cancels: HashMap<u32, Arc<AtomicBool>>,
}

impl Registry {
    fn insert(&mut self) -> (u32, Arc<AtomicBool>) {
        self.next += 1;
        let flag = Arc::new(AtomicBool::new(false));
        self.cancels.insert(self.next, flag.clone());
        (self.next, flag)
    }
}

/// Handle to the installed timers — kept by the runtime so intervals can be
/// force-cleared when a run finishes.
#[derive(Clone)]
pub struct Timers {
    reg: Arc<Mutex<Registry>>,
}

impl Timers {
    /// Install `setTimeout` / `setInterval` (+ clears, `queueMicrotask`) on
    /// the globals of `ctx`.
    pub async fn install(ctx: &AsyncContext) -> Result<Arc<Self>> {
        let this = Arc::new(Self {
            reg: Arc::new(Mutex::new(Registry {
                next: 0,
                cancels: HashMap::new(),
            })),
        });
        let inner = this.clone();
        ctx.async_with(async move |cx| {
            install_on(&cx, &inner)?;
            Ok::<(), rquickjs::Error>(())
        })
        .await?;
        Ok(this)
    }

    fn cancel(&self, id: u32) {
        if let Some(flag) = self.reg.lock().unwrap().cancels.remove(&id) {
            flag.store(true, Ordering::Relaxed);
        }
    }

    /// Drop every timer (used when a run finishes so intervals stop).
    pub fn clear_all(&self) {
        let mut reg = self.reg.lock().unwrap();
        for (_, flag) in reg.cancels.drain() {
            flag.store(true, Ordering::Relaxed);
        }
    }

    pub fn active(&self) -> usize {
        self.reg.lock().unwrap().cancels.len()
    }
}

/// Install the timer globals with one explicit `'js` so every closure's
/// captured context and JS arguments unify to the same lifetime.
fn install_on<'js>(cx: &Ctx<'js>, this: &Arc<Timers>) -> Result<()> {
    let g = cx.globals();

    let t = this.clone();
    let set_timeout = Function::new(
        cx.clone(),
        move |cx: Ctx<'js>, f: Function<'js>, ms: OptMs, rest: Rest<Value<'js>>| -> Result<u32> {
            schedule(&t, &cx, &f, &rest, ms.0.unwrap_or(0), false)
        },
    )?;
    g.set("setTimeout", set_timeout)?;

    let t = this.clone();
    let set_interval = Function::new(
        cx.clone(),
        move |cx: Ctx<'js>, f: Function<'js>, ms: OptMs, rest: Rest<Value<'js>>| -> Result<u32> {
            schedule(&t, &cx, &f, &rest, ms.0.unwrap_or(0), true)
        },
    )?;
    g.set("setInterval", set_interval)?;

    let t = this.clone();
    let clear = Function::new(cx.clone(), move |id: i64| t.cancel(id.max(0) as u32))?;
    g.set("clearTimeout", clear.clone())?;
    g.set("clearInterval", clear)?;

    let micro = Function::new(cx.clone(), move |cx: Ctx<'js>, f: Function<'js>| {
        cx.spawn(async move {
            let _ = f.call::<_, ()>(());
        });
    })?;
    g.set("queueMicrotask", micro)?;
    Ok(())
}

/// Schedule one timer: register a cancel flag, then spawn a future that
/// sleeps and fires the callback under the runtime lock.
fn schedule<'js>(
    this: &Arc<Timers>,
    cx: &Ctx<'js>,
    f: &Function<'js>,
    rest: &Rest<Value<'js>>,
    ms: u64,
    interval: bool,
) -> Result<u32> {
    let (id, flag) = this.reg.lock().unwrap().insert();
    let cancel = flag.clone();
    let func = f.clone();
    let args: Vec<Value<'js>> = rest.iter().cloned().collect();

    cx.spawn(async move {
        if interval {
            loop {
                tokio::time::sleep(Duration::from_millis(ms)).await;
                if cancel.load(Ordering::Relaxed) {
                    break;
                }
                let rest: Rest<Value<'js>> = Rest::from(args.clone());
                let _ = func.call::<(Rest<Value<'js>>,), ()>((rest,));
            }
        } else {
            tokio::time::sleep(Duration::from_millis(ms)).await;
            if !cancel.load(Ordering::Relaxed) {
                let rest: Rest<Value<'js>> = Rest::from(args);
                let _ = func.call::<(Rest<Value<'js>>,), ()>((rest,));
            }
        }
    });
    Ok(id)
}

/// Optional-ish milliseconds (accepts missing, number or string like "100").
struct OptMs(Option<u64>);

impl<'js> rquickjs::FromJs<'js> for OptMs {
    fn from_js(_ctx: &rquickjs::Ctx<'js>, value: rquickjs::Value<'js>) -> Result<Self> {
        if value.is_undefined() || value.is_null() {
            return Ok(OptMs(None));
        }
        let ms = value.get::<f64>()?;
        Ok(OptMs(Some(ms.max(0.0) as u64)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// setTimeout fires and the result is observable after the runtime idles.
    #[tokio::test(flavor = "current_thread")]
    async fn set_timeout_fires() {
        tokio::task::LocalSet::new()
        .run_until(async {
        eprintln!("step1: runtime created");
        let rt = AsyncRuntime::new().unwrap();
        let ctx = AsyncContext::full(&rt).await.unwrap();
        eprintln!("step2: ctx created");
        Timers::install(&ctx).await.unwrap();
        eprintln!("step3: timers installed");
        tokio::task::spawn_local(rt.drive());
        eprintln!("step4: drive spawned");
        ctx.with(|cx| {
            cx.eval::<(), _>(
                r#"globalThis.hits = 0; setTimeout(() => { globalThis.hits += 1 }, 10);"#,
            )
            .unwrap();
            0i32
        })
        .await;
        eprintln!("step5: eval done");
        rt.idle().await;
        eprintln!("step6: idle done");
        tokio::time::sleep(Duration::from_millis(30)).await;
        eprintln!("step7: slept");
        let hits: i32 = ctx.with(|cx| cx.globals().get("hits")).await.unwrap();
        eprintln!("step8: hits={hits}");
        assert_eq!(hits, 1);
        eprintln!("step9: test body complete, dropping rt/ctx");
        })
        .await;
    }

    #[tokio::test(flavor = "current_thread")]
    async fn clear_timeout_cancels() {
        tokio::task::LocalSet::new()
        .run_until(async {
        let rt = AsyncRuntime::new().unwrap();
        let ctx = AsyncContext::full(&rt).await.unwrap();
        let timers = Timers::install(&ctx).await.unwrap();
        tokio::task::spawn_local(rt.drive());
        ctx.with(|cx| {
            cx.eval::<(), _>(
                r#"globalThis.hits = 0; const id = setTimeout(() => { globalThis.hits += 1 }, 10); clearTimeout(id);"#,
            )
            .unwrap();
            0i32
        })
        .await;
        rt.idle().await;
        tokio::time::sleep(Duration::from_millis(30)).await;
        assert_eq!(timers.active(), 0);
        let hits: i32 = ctx.with(|cx| cx.globals().get("hits")).await.unwrap();
        assert_eq!(hits, 0);
        })
        .await;
    }

    #[tokio::test(flavor = "current_thread")]
    async fn interval_counts_then_clear_all_stops() {
        tokio::task::LocalSet::new()
        .run_until(async {
        let rt = AsyncRuntime::new().unwrap();
        let ctx = AsyncContext::full(&rt).await.unwrap();
        let timers = Timers::install(&ctx).await.unwrap();
        tokio::task::spawn_local(rt.drive());
        ctx.with(|cx| {
            cx.eval::<(), _>(
                r#"globalThis.hits = 0; setInterval(() => { globalThis.hits += 1 }, 5);"#,
            )
            .unwrap();
            0i32
        })
        .await;
        tokio::time::sleep(Duration::from_millis(40)).await;
        let hits: i32 = ctx.with(|cx| cx.globals().get("hits")).await.unwrap();
        assert!(hits >= 1, "interval fired at least once ({hits})");
        timers.clear_all();
        tokio::time::sleep(Duration::from_millis(30)).await;
        let hits2: i32 = ctx.with(|cx| cx.globals().get("hits")).await.unwrap();
        assert_eq!(hits, hits2, "no more fires after clear_all");
        })
        .await;
    }
}
