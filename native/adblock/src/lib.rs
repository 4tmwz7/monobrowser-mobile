//! MonoBrowser Mobile adblock engine.
//!
//! `engine` is the pure-Rust wrapper around brave/adblock-rust; `jni_api` exposes it to
//! `com.voluene.monobrowser.adblock.NativeAdblock` through a handle (pointer-as-jlong) API.

pub mod engine;
mod jni_api;
