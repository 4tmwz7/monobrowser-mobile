//! Thin, JNI-free wrapper around `adblock::Engine`. Everything testable on the host lives here.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::fs;
use std::io::Write;
use std::path::Path;

pub struct MonoEngine {
    engine: Engine,
}

impl MonoEngine {
    /// Builds an engine from raw filter list texts (EasyList / uBlock syntax).
    pub fn from_list_texts<I: IntoIterator<Item = String>>(lists: I) -> Self {
        let mut set = FilterSet::new(false);
        for text in lists {
            set.add_filter_list(text, ParseOptions::default());
        }
        Self { engine: Engine::new_with_filter_set(set) }
    }

    /// Builds an engine from list files. Unreadable files are skipped; returns the engine and the
    /// number of lists that were actually loaded.
    pub fn from_files<P: AsRef<Path>>(paths: &[P]) -> (Self, usize) {
        let mut loaded = 0;
        let mut set = FilterSet::new(false);
        for path in paths {
            match fs::read(path.as_ref()) {
                Ok(bytes) => {
                    set.add_filter_list(String::from_utf8_lossy(&bytes).into_owned(), ParseOptions::default());
                    loaded += 1;
                }
                Err(e) => log::warn!("skipping list {}: {e}", path.as_ref().display()),
            }
        }
        (Self { engine: Engine::new_with_filter_set(set) }, loaded)
    }

    /// Serializes to `path` atomically (temp file + rename).
    pub fn save(&self, path: &Path) -> std::io::Result<()> {
        let data = self.engine.serialize();
        let tmp = path.with_extension("tmp");
        {
            let mut f = fs::File::create(&tmp)?;
            f.write_all(&data)?;
            f.sync_all()?;
        }
        fs::rename(&tmp, path)
    }

    pub fn load(path: &Path) -> Result<Self, String> {
        let data = fs::read(path).map_err(|e| e.to_string())?;
        let mut engine = Engine::default();
        engine.deserialize(&data).map_err(|e| format!("{e:?}"))?;
        Ok(Self { engine })
    }

    /// True when the request should be blocked. `@@` exceptions and `$important` are handled by
    /// the engine; first/third-party is derived from `source_url` (the page URL).
    pub fn check(&self, url: &str, source_url: &str, request_type: &str) -> bool {
        match Request::new(url, source_url, request_type, "GET") {
            Ok(req) => self.engine.check_network_request(&req).should_block(),
            Err(_) => false,
        }
    }

    /// Hostname-specific cosmetic CSS: one `display:none` rule per selector so a single invalid
    /// selector cannot invalidate the others.
    pub fn cosmetic_css(&self, page_url: &str) -> String {
        let res = self.engine.url_cosmetic_resources(page_url);
        selectors_to_css(res.hide_selectors.iter().map(String::as_str))
    }

    /// Generic (class/id based) cosmetic CSS for the classes and ids found on a page.
    pub fn generic_css(&self, page_url: &str, classes: &[String], ids: &[String]) -> String {
        let res = self.engine.url_cosmetic_resources(page_url);
        if res.generichide {
            return String::new();
        }
        let selectors = self.engine.hidden_class_id_selectors(classes, ids, &res.exceptions);
        selectors_to_css(selectors.iter().map(String::as_str))
    }
}

fn selectors_to_css<'a>(selectors: impl Iterator<Item = &'a str>) -> String {
    let mut css = String::new();
    for s in selectors {
        // `{`/`}` would break out of the rule; newlines/`<` have no business in a selector.
        if s.is_empty() || s.contains(['{', '}', '\n', '\r', '<']) {
            continue;
        }
        css.push_str(s);
        css.push_str("{display:none!important}\n");
    }
    css
}

#[cfg(test)]
mod tests {
    use super::*;

    const RULES: &str = "\
||ads.example.com^
||tracker.example.net^$third-party
@@||ads.example.com/allowed.js^
||important.example.org^$important
@@||important.example.org^
||img.example.org^$image
example.com##.ad-banner
##.sponsored
";

    fn engine() -> MonoEngine {
        MonoEngine::from_list_texts([RULES.to_string()])
    }

    #[test]
    fn blocks_domain_rule() {
        let e = engine();
        assert!(e.check("https://ads.example.com/x.js", "https://page.org/", "script"));
        assert!(!e.check("https://good.example.com/x.js", "https://page.org/", "script"));
    }

    #[test]
    fn exception_rule_wins() {
        let e = engine();
        assert!(!e.check("https://ads.example.com/allowed.js", "https://page.org/", "script"));
    }

    #[test]
    fn important_beats_exception() {
        let e = engine();
        assert!(e.check("https://important.example.org/a", "https://page.org/", "script"));
    }

    #[test]
    fn third_party_option() {
        let e = engine();
        let u = "https://tracker.example.net/p.gif";
        assert!(e.check(u, "https://page.org/", "image"));
        // first party: same registrable domain
        assert!(!e.check(u, "https://www.example.net/", "image"));
    }

    #[test]
    fn type_option() {
        let e = engine();
        assert!(e.check("https://img.example.org/a.png", "https://page.org/", "image"));
        assert!(!e.check("https://img.example.org/a.js", "https://page.org/", "script"));
    }

    #[test]
    fn invalid_url_is_not_blocked() {
        assert!(!engine().check("not a url", "https://page.org/", "script"));
    }

    #[test]
    fn cosmetic_hostname_rules() {
        let e = engine();
        let css = e.cosmetic_css("https://example.com/page");
        assert!(css.contains(".ad-banner{display:none!important}"), "{css}");
        assert!(!e.cosmetic_css("https://other.org/").contains(".ad-banner"));
    }

    #[test]
    fn generic_class_selectors() {
        let e = engine();
        let css = e.generic_css("https://other.org/", &["sponsored".into(), "x".into()], &[]);
        assert!(css.contains(".sponsored{display:none!important}"), "{css}");
        assert!(e.generic_css("https://other.org/", &["x".into()], &[]).is_empty());
    }

    #[test]
    fn selector_sanitizing() {
        let css = selectors_to_css(["a{b}", ".ok", ""].into_iter());
        assert_eq!(css, ".ok{display:none!important}\n");
    }

    #[test]
    fn serialize_roundtrip() {
        let dir = std::env::temp_dir().join(format!("monoadblock-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("engine.bin");
        engine().save(&path).unwrap();
        let loaded = MonoEngine::load(&path).unwrap();
        assert!(loaded.check("https://ads.example.com/x.js", "https://page.org/", "script"));
        assert!(!loaded.check("https://ads.example.com/allowed.js", "https://page.org/", "script"));
        fs::write(&path, b"garbage").unwrap();
        assert!(MonoEngine::load(&path).is_err());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn from_files_skips_missing() {
        let dir = std::env::temp_dir().join(format!("monoadblock-files-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let list = dir.join("l.txt");
        fs::write(&list, RULES).unwrap();
        let (e, n) = MonoEngine::from_files(&[list, dir.join("missing.txt")]);
        assert_eq!(n, 1);
        assert!(e.check("https://ads.example.com/x.js", "https://page.org/", "script"));
        let _ = fs::remove_dir_all(&dir);
    }

    /// Manual smoke test against real lists: `MONO_LISTS_DIR=/path cargo test --release -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn real_lists_smoke() {
        let dir = std::env::var("MONO_LISTS_DIR").expect("MONO_LISTS_DIR");
        let mut paths: Vec<_> = fs::read_dir(&dir).unwrap().map(|e| e.unwrap().path()).collect();
        paths.sort();
        let t = std::time::Instant::now();
        let (e, n) = MonoEngine::from_files(&paths);
        eprintln!("built from {n} lists in {:?}", t.elapsed());
        let out = std::env::temp_dir().join("monoadblock-real.bin");
        e.save(&out).unwrap();
        eprintln!("serialized {} bytes", fs::metadata(&out).unwrap().len());
        let t = std::time::Instant::now();
        let e = MonoEngine::load(&out).unwrap();
        eprintln!("loaded in {:?}", t.elapsed());
        for (u, ty) in [
            ("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", "script"),
            ("https://www.google-analytics.com/analytics.js", "script"),
            ("https://example.com/style.css", "stylesheet"),
        ] {
            eprintln!("{u} -> {}", e.check(u, "https://news.example.org/", ty));
        }
        let t = std::time::Instant::now();
        for _ in 0..1000 {
            e.check("https://cdn.example.org/assets/app.js", "https://news.example.org/", "script");
        }
        eprintln!("1000 checks: {:?}", t.elapsed());
        eprintln!("cosmetic css bytes (youtube.com): {}", e.cosmetic_css("https://www.youtube.com/").len());
    }
}
