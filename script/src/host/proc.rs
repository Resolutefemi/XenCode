//! `xen.proc` — spawn processes, capture stdout/stderr/exit.
//!
//! `spawn(cmd, args?, opts?)` runs the process to completion via tokio and
//! resolves `{ exitCode, stdout, stderr, success }`; `run(...)` additionally
//! throws when the exit code is non-zero. Every spawn passes the `proc`
//! permission gate (`true`, command allowlist, or GUI consent).

use rquickjs::function::Opt;
use rquickjs::prelude::Async;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};

use crate::permissions::PermissionState;

use super::{js_err, state};

pub struct XenProc;

impl ModuleDef for XenProc {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("spawn")?;
        decl.declare("run")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let st = state(ctx)?;
        let perms = st.perms.clone();

        let p = perms.clone();
        let spawn = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, program: String, args: Opt<Vec<String>>, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move {
                    proc_spawn(&cx, &perms, program, args.0, opts.0)
                        .await
                        .map(|o| o.into_value())
                }
            }),
        )?;
        exports.export("spawn", spawn)?;

        // run(): same, but throws a JS error on non-zero exit (shell-ish)
        let p = perms.clone();
        let run = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, program: String, args: Opt<Vec<String>>, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { proc_run(&cx, &perms, program, args.0, opts.0).await }
            }),
        )?;
        exports.export("run", run)?;

        Ok(())
    }
}

struct ProcOpts {
    cwd: Option<String>,
    env: Vec<(String, String)>,
    stdin: Option<Vec<u8>>,
}

fn read_opts<'js>(opts: Option<&Object<'js>>) -> JsResult<ProcOpts> {
    let mut spec = ProcOpts {
        cwd: None,
        env: Vec::new(),
        stdin: None,
    };
    let Some(obj) = opts else { return Ok(spec) };
    if let Some(cwd) = obj.get::<_, Option<String>>("cwd")? {
        spec.cwd = Some(cwd);
    }
    if let Some(env) = obj.get::<_, Option<Object>>("env")? {
        for pair in env.props::<String, Value>() {
            let (k, v) = pair.map_err(|e| js_err(format!("env prop: {e}")))?;
            let val = v
                .as_string()
                .map(|s| s.to_string())
                .transpose()?
                .unwrap_or_default();
            spec.env.push((k, val));
        }
    }
    if let Some(stdin) = obj.get::<_, Option<String>>("stdin")? {
        spec.stdin = Some(stdin.into_bytes());
    }
    Ok(spec)
}

async fn proc_spawn<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    program: String,
    args: Option<Vec<String>>,
    opts: Option<Object<'js>>,
) -> JsResult<Object<'js>> {
    perms.check_proc(&program).map_err(js_err)?;
    let spec = read_opts(opts.as_ref())?;
    let output = run_process(&program, args.unwrap_or_default(), &spec).await?;
    build_output(cx, output)
}

async fn proc_run<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    program: String,
    args: Option<Vec<String>>,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    perms.check_proc(&program).map_err(js_err)?;
    let spec = read_opts(opts.as_ref())?;
    let output = run_process(&program, args.unwrap_or_default(), &spec).await?;
    if !output.status.success() {
        return Err(js_err(format!(
            "`{program}` exited with code {}: {}",
            output.status.code().unwrap_or(-1),
            String::from_utf8_lossy(&output.stderr).trim()
        )));
    }
    Ok(build_output(cx, output)?.into_value())
}

async fn run_process(
    program: &str,
    args: Vec<String>,
    spec: &ProcOpts,
) -> JsResult<std::process::Output> {
    use tokio::io::AsyncWriteExt;
    let mut cmd = tokio::process::Command::new(program);
    cmd.args(&args);
    if let Some(cwd) = &spec.cwd {
        cmd.current_dir(cwd);
    }
    for (k, v) in &spec.env {
        cmd.env(k, v);
    }
    cmd.stdin(std::process::Stdio::piped());
    cmd.stdout(std::process::Stdio::piped());
    cmd.stderr(std::process::Stdio::piped());

    let mut child = cmd
        .spawn()
        .map_err(|e| js_err(format!("spawn `{program}` failed: {e}")))?;
    if let Some(data) = &spec.stdin {
        if let Some(mut stdin) = child.stdin.take() {
            let _ = stdin.write_all(data).await;
            let _ = stdin.shutdown().await;
        }
    }
    child
        .wait_with_output()
        .await
        .map_err(|e| js_err(format!("`{program}` wait failed: {e}")))
}

fn build_output<'js>(cx: &Ctx<'js>, output: std::process::Output) -> JsResult<Object<'js>> {
    let obj = Object::new(cx.clone())?;
    obj.set("exitCode", output.status.code())?;
    obj.set("stdout", String::from_utf8_lossy(&output.stdout).to_string())?;
    obj.set("stderr", String::from_utf8_lossy(&output.stderr).to_string())?;
    obj.set("success", output.status.success())?;
    Ok(obj)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(flavor = "multi_thread")]
    async fn spawn_echo() {
        let out = run_process(
            "echo",
            vec!["hi".into()],
            &ProcOpts {
                cwd: None,
                env: vec![],
                stdin: None,
            },
        )
        .await
        .unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout).trim(), "hi");
        assert!(out.status.success());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn spawn_stdin_cat() {
        let out = run_process(
            "cat",
            vec![],
            &ProcOpts {
                cwd: None,
                env: vec![],
                stdin: Some(b"piped".to_vec()),
            },
        )
        .await
        .unwrap();
        assert_eq!(String::from_utf8_lossy(&out.stdout), "piped");
    }
}
