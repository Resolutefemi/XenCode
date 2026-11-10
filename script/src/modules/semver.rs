//! `xen/semver` — version parse/compare/satisfies (semver crate).

use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::host::js_err;

pub struct XenSemver;

impl ModuleDef for XenSemver {
    fn declare(decl: &Declarations) -> JsResult<()> {
        for name in [
            "valid", "parse", "satisfies", "compare", "gt", "gte", "lt", "lte",
            "eq", "neq", "coerce", "major", "minor", "patch",
        ] {
            decl.declare(name)?;
        }
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let valid = Function::new(ctx.clone(), |v: String| {
            if semver::Version::parse(&v).is_ok() {
                Some(v)
            } else {
                None
            }
        })?;
        exports.export("valid", valid)?;

        let parse = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, v: String| -> JsResult<Object<'js>> {
            let ver = semver::Version::parse(&v).map_err(|e| js_err(format!("semver `{v}`: {e}")))?;
            let obj = Object::new(cx.clone())?;
            obj.set("major", ver.major as f64)?;
            obj.set("minor", ver.minor as f64)?;
            obj.set("patch", ver.patch as f64)?;
            obj.set(
                "prerelease",
                if ver.pre.is_empty() {
                    None::<String>
                } else {
                    Some(ver.pre.to_string())
                },
            )?;
            obj.set(
                "build",
                if ver.build.is_empty() {
                    None::<String>
                } else {
                    Some(ver.build.to_string())
                },
            )?;
            obj.set("version", ver.to_string())?;
            Ok(obj)
        })?;
        exports.export("parse", parse)?;

        let satisfies = Function::new(ctx.clone(), |v: String, range: String| {
            let ver = semver::Version::parse(&v)
                .map_err(|e| js_err(format!("semver `{v}`: {e}")))?;
            let req = semver::VersionReq::parse(&range)
                .map_err(|e| js_err(format!("semver range `{range}`: {e}")))?;
            Ok::<bool, rquickjs::Error>(req.matches(&ver))
        })?;
        exports.export("satisfies", satisfies)?;

        let compare = Function::new(ctx.clone(), |a: String, b: String| {
            let va = parse_ver(&a)?;
            let vb = parse_ver(&b)?;
            Ok::<i8, rquickjs::Error>(match va.cmp(&vb) {
                std::cmp::Ordering::Less => -1,
                std::cmp::Ordering::Equal => 0,
                std::cmp::Ordering::Greater => 1,
            })
        })?;
        exports.export("compare", compare)?;

        let mk = |op: fn(&semver::Version, &semver::Version) -> bool| {
            Function::new(ctx.clone(), move |a: String, b: String| {
                let va = parse_ver(&a)?;
                let vb = parse_ver(&b)?;
                Ok::<bool, rquickjs::Error>(op(&va, &vb))
            })
        };
        exports.export("gt", mk(|a, b| a > b))?;
        exports.export("gte", mk(|a, b| a >= b))?;
        exports.export("lt", mk(|a, b| a < b))?;
        exports.export("lte", mk(|a, b| a <= b))?;
        exports.export("eq", mk(|a, b| a == b))?;
        exports.export("neq", mk(|a, b| a != b))?;

        let coerce = Function::new(ctx.clone(), |s: String| coerce_version(&s))?;
        exports.export("coerce", coerce)?;

        let major = Function::new(ctx.clone(), |v: String| Ok::<f64, rquickjs::Error>(parse_ver(&v)?.major as f64))?;
        exports.export("major", major)?;
        let minor = Function::new(ctx.clone(), |v: String| Ok::<f64, rquickjs::Error>(parse_ver(&v)?.minor as f64))?;
        exports.export("minor", minor)?;
        let patch = Function::new(ctx.clone(), |v: String| Ok::<f64, rquickjs::Error>(parse_ver(&v)?.patch as f64))?;
        exports.export("patch", patch)?;

        Ok(())
    }
}

fn parse_ver(v: &str) -> Result<semver::Version, rquickjs::Error> {
    let v = coerce_version(v).unwrap_or_else(|| v.to_string());
    semver::Version::parse(&v).map_err(|e| js_err(format!("semver `{v}`: {e}")))
}

/// Pull a bare `x.y.z` (or `x.y` → `.0`) out of a noisy string.
pub fn coerce_version(s: &str) -> Option<String> {
    let mut nums: Vec<String> = Vec::new();
    let mut chars = s.chars().peekable();
    while let Some(c) = chars.next() {
        if c.is_ascii_digit() {
            let mut num = String::from(c);
            while let Some(&d) = chars.peek() {
                if d.is_ascii_digit() {
                    num.push(d);
                    chars.next();
                } else {
                    break;
                }
            }
            nums.push(num);
        }
    }
    match nums.len() {
        0 => None,
        1 => Some(format!("{}.0.0", nums[0])),
        2 => Some(format!("{}.{}.0", nums[0], nums[1])),
        _ => Some(format!("{}.{}.{}", nums[0], nums[1], nums[2])),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_compares() {
        let v = semver::Version::parse("3.17.2").unwrap();
        assert_eq!((v.major, v.minor, v.patch), (3, 17, 2));
        let req = semver::VersionReq::parse(">=3.0").unwrap();
        assert!(req.matches(&v));
        assert!(semver::Version::parse("3.17.2").unwrap() > semver::Version::parse("3.17.1").unwrap());
    }

    #[test]
    fn coerces() {
        assert_eq!(coerce_version("v3.17.2-beta").unwrap(), "3.17.2");
        assert_eq!(coerce_version("version 2").unwrap(), "2.0.0");
        assert_eq!(coerce_version("no digits"), None);
    }
}
