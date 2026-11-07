//! 可选的 Rust 脚本引擎：rquickjs + JNI。
//! 与 Kotlin 的接口只有两个回调：`hostCall(op,a,b)`、`hostSend(name,json)`，
//! 网络/加密/日志都在 Kotlin 侧（RustEngine.kt），Rust 只负责跑 JS。
use std::sync::{Arc, Mutex};

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::sys::{jint, jlong, jstring};
use jni::{JNIEnv, JavaVM};
use rquickjs::{Context, Function, Runtime};

const SHIM: &str = include_str!("shim.js");

struct Host {
    jvm: JavaVM,
    obj: GlobalRef,
}

impl Host {
    fn call(&self, op: &str, a: &str, b: &str) -> String {
        let mut env = match self.jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return String::new(),
        };
        let (op_s, a_s, b_s) = match (env.new_string(op), env.new_string(a), env.new_string(b)) {
            (Ok(x), Ok(y), Ok(z)) => (x, y, z),
            _ => return String::new(),
        };
        let res = env.call_method(
            self.obj.as_obj(),
            "hostCall",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            &[
                JValue::Object(&op_s),
                JValue::Object(&a_s),
                JValue::Object(&b_s),
            ],
        );
        match res {
            Ok(v) => match v.l() {
                Ok(o) if !o.is_null() => {
                    let js = JString::from(o);
                    env.get_string(&js).map(|s| s.into()).unwrap_or_default()
                }
                _ => String::new(),
            },
            Err(_) => String::new(),
        }
    }

    fn send(&self, name: &str, json: &str) {
        let mut env = match self.jvm.attach_current_thread() {
            Ok(e) => e,
            Err(_) => return,
        };
        let (n_s, j_s) = match (env.new_string(name), env.new_string(json)) {
            (Ok(x), Ok(y)) => (x, y),
            _ => return,
        };
        let _ = env.call_method(
            self.obj.as_obj(),
            "hostSend",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            &[JValue::Object(&n_s), JValue::Object(&j_s)],
        );
    }
}

struct Shared {
    inited: Mutex<Option<String>>,
    result: Mutex<Option<String>>,
    applied: Mutex<Option<String>>,
}

struct Engine {
    runtime: Runtime,
    ctx: Context,
    host: Arc<Host>,
    shared: Arc<Shared>,
}

impl Engine {
    fn pump(&self) {
        for _ in 0..200_000 {
            match self.runtime.execute_pending_job() {
                Ok(true) => continue,
                _ => break,
            }
        }
    }

    fn eval_unit(&self, code: &str) -> Result<(), String> {
        self.ctx
            .with(|ctx| ctx.eval::<(), _>(code))
            .map_err(|e| format!("{e}"))
    }
}

fn new_engine(env: &mut JNIEnv, host_obj: JObject) -> Result<Engine, String> {
    let jvm = env.get_java_vm().map_err(|e| e.to_string())?;
    let obj = env.new_global_ref(host_obj).map_err(|e| e.to_string())?;
    let host = Arc::new(Host { jvm, obj });
    let runtime = Runtime::new().map_err(|e| format!("{e}"))?;
    let ctx = Context::full(&runtime).map_err(|e| format!("{e}"))?;
    let shared = Arc::new(Shared {
        inited: Mutex::new(None),
        result: Mutex::new(None),
        applied: Mutex::new(None),
    });
    let engine = Engine {
        runtime,
        ctx,
        host: host.clone(),
        shared: shared.clone(),
    };
    engine
        .ctx
        .with(|ctx| -> Result<(), rquickjs::Error> {
            let globals = ctx.globals();

            let h1 = host.clone();
            let call = Function::new(ctx.clone(), move |op: String, a: String, b: String| -> String {
                h1.call(&op, &a, &b)
            })?;
            globals.set("__hostCall", call)?;

            let h2 = host.clone();
            let s1 = shared.clone();
            let send = Function::new(ctx.clone(), move |name: String, json: String| {
                match name.as_str() {
                    "inited" => *s1.inited.lock().unwrap() = Some(json.clone()),
                    "__result" => *s1.result.lock().unwrap() = Some(json.clone()),
                    _ => {}
                }
                h2.send(&name, &json);
            })?;
            globals.set("__hostSendRaw", send)?;

            globals.set("__lxScriptInfo", rquickjs::Object::new(ctx.clone())?)?;
            let _ = ctx.eval::<(), _>(SHIM);
            Ok(())
        })
        .map_err(|e| format!("{e}"))?;
    Ok(engine)
}

fn jstr(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(v) => v.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lxdl_engine_RustEngine_nativeCreate(
    mut env: JNIEnv,
    _class: JObject,
    host: JObject,
) -> jlong {
    match new_engine(&mut env, host) {
        Ok(e) => Box::into_raw(Box::new(e)) as jlong,
        Err(e) => {
            let _ = env.throw_new("java/lang/IllegalStateException", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lxdl_engine_RustEngine_nativeLoadScript(
    mut env: JNIEnv,
    _class: JObject,
    handle: jlong,
    entry: JString,
    meta: JString,
    code: JString,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let engine = unsafe { &*(handle as *const Engine) };
    let entry: String = match env.get_string(&entry) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let meta: String = match env.get_string(&meta) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let code: String = match env.get_string(&code) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    if engine.shared.applied.lock().unwrap().as_deref() == Some(entry.as_str()) {
        return std::ptr::null_mut();
    }
    *engine.shared.inited.lock().unwrap() = None;
    let script = format!("globalThis.__lxScriptInfo = {meta};\n(function(){{\n{code}\n}})();");
    if let Err(e) = engine.eval_unit(&script) {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            format!("脚本执行失败：{e}"),
        );
        return std::ptr::null_mut();
    }
    engine.pump();
    let out = engine.shared.inited.lock().unwrap().clone();
    match out {
        Some(json) => {
            *engine.shared.applied.lock().unwrap() = Some(entry);
            jstr(&mut env, &json)
        }
        None => {
            let _ = env.throw_new(
                "java/lang/IllegalStateException",
                "音源初始化超时（脚本没有上报 inited）",
            );
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lxdl_engine_RustEngine_nativeCall(
    mut env: JNIEnv,
    _class: JObject,
    handle: jlong,
    source: JString,
    action: JString,
    info: JString,
    timeout_ms: jint,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let engine = unsafe { &*(handle as *const Engine) };
    let source: String = match env.get_string(&source) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let action: String = match env.get_string(&action) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let info: String = match env.get_string(&info) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    *engine.shared.result.lock().unwrap() = None;
    let js = format!(
        "__lxAction({}, {}, {});",
        serde_json::to_string(&source).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(&action).unwrap_or_else(|_| "\"\"".into()),
        serde_json::to_string(&info).unwrap_or_else(|_| "\"{}\"".into())
    );
    if let Err(e) = engine.eval_unit(&js) {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            format!("音源脚本调用失败：{e}"),
        );
        return std::ptr::null_mut();
    }
    let deadline = std::time::Instant::now()
        + std::time::Duration::from_millis((timeout_ms.max(1000) as u64) + 5_000);
    loop {
        engine.pump();
        if let Some(v) = engine.shared.result.lock().unwrap().clone() {
            return jstr(&mut env, &v);
        }
        if std::time::Instant::now() > deadline {
            let _ = env.throw_new("java/lang/IllegalStateException", "音源响应超时");
            return std::ptr::null_mut();
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_lxdl_engine_RustEngine_nativeDestroy(
    _env: JNIEnv,
    _class: JObject,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Engine));
        }
    }
}
