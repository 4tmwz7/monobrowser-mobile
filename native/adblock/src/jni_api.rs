//! JNI surface. Kotlin counterpart: `com.voluene.monobrowser.adblock.NativeAdblock`.
//!
//! Handles are `Box<MonoEngine>` raw pointers. The Kotlin wrapper guarantees `nativeFree` is never
//! called while another call on the same handle is in flight (read/write lock). `MonoEngine` is
//! `Send + Sync` (adblock built without `single-thread`), so concurrent `check` calls are fine.

use crate::engine::MonoEngine;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jlong};
use jni::{Env, EnvUnowned};
use std::path::Path;

type JniResult<T> = Result<T, jni::errors::Error>;

fn engine_ref<'a>(handle: jlong) -> Option<&'a MonoEngine> {
    if handle == 0 {
        None
    } else {
        // SAFETY: handle was produced by `into_handle` and is kept alive by the Kotlin wrapper.
        Some(unsafe { &*(handle as *const MonoEngine) })
    }
}

fn into_handle(engine: MonoEngine) -> jlong {
    Box::into_raw(Box::new(engine)) as jlong
}

fn jstr(env: &Env<'_>, s: &JString<'_>) -> JniResult<String> {
    s.try_to_string(env)
}

fn split_lines(s: &str) -> Vec<String> {
    s.split('\n').filter(|p| !p.is_empty()).map(str::to_owned).collect()
}

/// One-time init: routes Rust `log` output to logcat (tag `MonoAdblock`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeInit<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
) {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("MonoAdblock"),
    );
}

/// Builds an engine from newline-separated list file paths. Returns 0 when no list could be read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeCreateFromFiles<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    paths: JString<'l>,
) -> jlong {
    env.with_env(|env| -> JniResult<jlong> {
        let paths = split_lines(&jstr(env, &paths)?);
        let (engine, loaded) = MonoEngine::from_files(&paths);
        log::info!("engine built from {loaded}/{} lists", paths.len());
        Ok(if loaded == 0 { 0 } else { into_handle(engine) })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Loads a serialized engine; returns 0 when the file is missing, corrupt or from another version.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeLoad<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
) -> jlong {
    env.with_env(|env| -> JniResult<jlong> {
        let path = jstr(env, &path)?;
        Ok(match MonoEngine::load(Path::new(&path)) {
            Ok(e) => into_handle(e),
            Err(err) => {
                log::warn!("cache load failed: {err}");
                0
            }
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeSave<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    path: JString<'l>,
) -> jboolean {
    env.with_env(|env| -> JniResult<jboolean> {
        let path = jstr(env, &path)?;
        Ok(match engine_ref(handle).map(|e| e.save(Path::new(&path))) {
            Some(Ok(())) => true,
            Some(Err(err)) => {
                log::warn!("cache save failed: {err}");
                false
            }
            None => false,
        })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// `request_type`: script, image, stylesheet, xhr, sub_frame, document, font, media, other, ...
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeCheck<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    url: JString<'l>,
    source_url: JString<'l>,
    request_type: JString<'l>,
) -> jboolean {
    env.with_env(|env| -> JniResult<jboolean> {
        let Some(engine) = engine_ref(handle) else { return Ok(false) };
        let url = jstr(env, &url)?;
        let source = jstr(env, &source_url)?;
        let ty = jstr(env, &request_type)?;
        Ok(engine.check(&url, &source, &ty))
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Hostname-specific cosmetic CSS for a page URL ("" when none).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeCosmeticCss<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    page_url: JString<'l>,
) -> JString<'l> {
    env.with_env(|env| -> JniResult<JString<'l>> {
        let css = match engine_ref(handle) {
            Some(e) => e.cosmetic_css(&jstr(env, &page_url)?),
            None => String::new(),
        };
        JString::from_str(env, css)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

/// Generic cosmetic CSS for the given classes / ids (each newline-separated).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeGenericCss<'l>(
    mut env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
    page_url: JString<'l>,
    classes: JString<'l>,
    ids: JString<'l>,
) -> JString<'l> {
    env.with_env(|env| -> JniResult<JString<'l>> {
        let css = match engine_ref(handle) {
            Some(e) => {
                let page = jstr(env, &page_url)?;
                let classes = split_lines(&jstr(env, &classes)?);
                let ids = split_lines(&jstr(env, &ids)?);
                e.generic_css(&page, &classes, &ids)
            }
            None => String::new(),
        };
        JString::from_str(env, css)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_voluene_monobrowser_adblock_NativeAdblock_nativeFree<'l>(
    _env: EnvUnowned<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: see module docs; each handle is freed exactly once by the Kotlin wrapper.
        drop(unsafe { Box::from_raw(handle as *mut MonoEngine) });
    }
}
