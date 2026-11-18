//! Thin, JNI-free wrapper around `adblock::Engine`. Everything testable on the host lives here.

use adblock::cosmetic_filter_cache::ProceduralOrActionFilter;
use adblock::filters::cosmetic::CosmeticFilterAction;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use std::fs;
use std::io::Write;
use std::path::Path;

/// MonoBrowser's own fixes, appended to the downloaded lists whenever an engine is built. Bump
/// `FilterLists.SET_VERSION` (Kotlin) after changing this: it names the serialized engine cache.
pub const BUILTIN_RULES: &str = "\
! Reddit leaves `rpl-scroll-lock` (overflow:hidden) on <body> after EasyList Cookie hides the consent sheet.
reddit.com##body.rpl-scroll-lock:has(#data-protection-consent-sheet[open]):style(overflow: visible !important)
";

fn add_lists<I: IntoIterator<Item = String>>(lists: I) -> FilterSet {
    let mut set = FilterSet::new(false);
    for text in lists {
        set.add_filter_list(text, ParseOptions::default());
    }
    set.add_filter_list(BUILTIN_RULES.to_string(), ParseOptions::default());
    set
}

pub struct MonoEngine {
    engine: Engine,
}

impl MonoEngine {
    /// Builds an engine from raw filter list texts (EasyList / uBlock syntax).
    pub fn from_list_texts<I: IntoIterator<Item = String>>(lists: I) -> Self {
        Self { engine: Engine::new_with_filter_set(add_lists(lists)) }
    }

    /// Builds an engine from list files. Unreadable files are skipped; returns the engine and the
    /// number of lists that were actually loaded.
    pub fn from_files<P: AsRef<Path>>(paths: &[P]) -> (Self, usize) {
        let mut texts = Vec::new();
        for path in paths {
            match fs::read(path.as_ref()) {
                Ok(bytes) => texts.push(String::from_utf8_lossy(&bytes).into_owned()),
                Err(e) => log::warn!("skipping list {}: {e}", path.as_ref().display()),
            }
        }
        let loaded = texts.len();
        (Self { engine: Engine::new_with_filter_set(add_lists(texts)) }, loaded)
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
    /// selector cannot invalidate the others, followed by the `:style()` rules that are plain CSS.
    pub fn cosmetic_css(&self, page_url: &str) -> String {
        let res = self.engine.url_cosmetic_resources(page_url);
        let mut css = selectors_to_css(res.hide_selectors.iter().map(String::as_str));
        css.push_str(&style_actions_to_css(res.procedural_actions.iter().map(String::as_str)));
        css
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
        if !safe_selector(s) {
            continue;
        }
        css.push_str(s);
        css.push_str("{display:none!important}\n");
    }
    css
}

/// `{`/`}` would break out of the rule; newlines/`<` have no business in a selector; a leading `@`
/// would turn the "selector" into an at-rule (`@import url(...)` loads a stylesheet).
fn safe_selector(s: &str) -> bool {
    !s.is_empty() && !s.contains(['{', '}', '\n', '\r', '<']) && !s.trim_start().starts_with('@')
}

/// uBO/ABP procedural pseudo-classes: not CSS, the browser would drop the whole rule.
fn is_procedural(selector: &str) -> bool {
    const OPS: [&str; 12] = [
        ":has-text(", ":upward(", ":xpath(", ":matches-css(", ":matches-css-before(", ":matches-css-after(",
        ":min-text-length(", ":watch-attr(", ":matches-path(", ":matches-attr(", ":others(", ":-abp-",
    ];
    OPS.iter().any(|op| selector.contains(op))
}

/// Turns JSON-encoded `ProceduralOrActionFilter`s into CSS rules. Only `selector:style(...)` with a
/// single plain CSS selector is supported; procedural operators (`:has-text`, ...) and the
/// remove/remove-class/remove-attr actions need in-page JS and are skipped. Like uBO, every
/// declaration gets `!important`.
fn style_actions_to_css<'a>(filters: impl Iterator<Item = &'a str>) -> String {
    let mut css = String::new();
    for json in filters {
        let Ok(filter) = serde_json::from_str::<ProceduralOrActionFilter>(json) else { continue };
        if !matches!(filter.action, Some(CosmeticFilterAction::Style(_))) {
            continue;
        }
        let Some((selector, style)) = filter.as_css() else { continue };
        if !safe_selector(&selector) || is_procedural(&selector) {
            continue;
        }
        let Some(style) = important_declarations(&style) else { continue };
        css.push_str(&selector);
        css.push('{');
        css.push_str(&style);
        css.push_str("}\n");
    }
    css
}

/// Normalizes a `:style()` argument to `a:b!important;c:d!important`. `None` for anything that
/// could escape the rule or load resources.
fn important_declarations(style: &str) -> Option<String> {
    let lower = style.to_ascii_lowercase();
    if style.contains(['{', '}', '\n', '\r', '<', '\\', '@', '"', '\'']) || lower.contains("url(") || lower.contains("/*") || lower.contains("expression(") {
        return None;
    }
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut start = 0;
    let mut parts = Vec::new();
    for (i, c) in style.char_indices() {
        match c {
            '(' => depth += 1,
            ')' => depth -= 1,
            ';' if depth == 0 => {
                parts.push(&style[start..i]);
                start = i + 1;
            }
            _ => {}
        }
    }
    parts.push(&style[start..]);
    if depth != 0 {
        return None;
    }
    for decl in parts.into_iter().map(str::trim).filter(|d| !d.is_empty()) {
        let (name, value) = decl.split_once(':')?;
        let (name, value) = (name.trim(), value.trim());
        if name.is_empty() || value.is_empty() {
            return None;
        }
        let value = value.trim_end_matches(|c: char| c == ' ').to_string();
        let has_important = value.to_ascii_lowercase().replace(' ', "").ends_with("!important");
        out.push(if has_important { format!("{name}:{value}") } else { format!("{name}:{value}!important") });
    }
    if out.is_empty() { None } else { Some(out.join(";")) }
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

    fn css_for(rules: &str, url: &str) -> String {
        MonoEngine::from_list_texts([rules.to_string()]).cosmetic_css(url)
    }

    #[test]
    fn style_rule_becomes_css() {
        let css = css_for("example.com##.x:style(color: red; margin:0 !important)", "https://example.com/");
        assert!(css.contains(".x{color:red!important;margin:0 !important}\n"), "{css}");
        assert!(!css_for("example.com##.x:style(color: red)", "https://other.org/").contains(".x{"));
    }

    #[test]
    fn procedural_and_remove_rules_are_skipped() {
        let rules = "example.com##.a:has-text(promo):style(color:red)\nexample.com##.b:remove()\nexample.com##.c:remove-attr(onclick)\nexample.com##.d:upward(2):style(color:red)\nexample.com##.e:style(color:red)";
        let css = css_for(rules, "https://example.com/");
        assert!(css.contains(".e{color:red!important}"), "{css}");
        for bad in [".a", ".b", ".c", ".d"] {
            assert!(!css.contains(&format!("{bad}{{")), "{bad} leaked: {css}");
            assert!(!css.contains(&format!("{bad}:")), "{bad} leaked: {css}");
        }
    }

    #[test]
    fn at_rule_selectors_are_rejected() {
        let css = css_for("example.com##@import url(https://evil.example/x.css); zz\nexample.com##@import url(https://evil.example/y.css); a:style(color:red)\nexample.com##.ok", "https://example.com/");
        assert!(!css.contains("@import"), "{css}");
        assert!(css.contains(".ok{display:none!important}"), "{css}");
    }

    #[test]
    fn malicious_style_is_rejected() {
        for style in ["x:y}body{display:none", "background:url(https://evil/x.png)", "a:b;}", "a:b\\9", "a:'b'", "a", "a:b<c"] {
            assert_eq!(important_declarations(style), None, "{style}");
        }
        assert_eq!(important_declarations("a:calc(1px + 2px); b:c").as_deref(), Some("a:calc(1px + 2px)!important;b:c!important"));
        let json = r#"{"selector":[{"type":"css-selector","arg":"a{b}"}],"action":{"type":"style","arg":"color:red"}}"#;
        assert_eq!(style_actions_to_css([json].into_iter()), "");
        let json = r#"{"selector":[{"type":"css-selector","arg":".a"}],"action":{"type":"style","arg":"color:red;}x{y:z"}}"#;
        assert_eq!(style_actions_to_css([json].into_iter()), "");
        assert_eq!(style_actions_to_css(["not json"].into_iter()), "");
    }

    #[test]
    fn builtin_reddit_rule() {
        let e = MonoEngine::from_list_texts(Vec::<String>::new());
        let css = e.cosmetic_css("https://www.reddit.com/r/x/");
        assert_eq!(css, "body.rpl-scroll-lock:has(#data-protection-consent-sheet[open]){overflow:visible !important}\n");
        assert!(!e.cosmetic_css("https://example.com/").contains("rpl-scroll-lock"));
    }

    #[test]
    fn has_selector_in_style_rule() {
        let rules = "reddit.com##body.rpl-scroll-lock:has(#data-protection-consent-sheet[open]):style(overflow: visible !important)";
        let json = MonoEngine::from_list_texts([rules.to_string()])
            .engine
            .url_cosmetic_resources("https://www.reddit.com/")
            .procedural_actions;
        eprintln!("has-in-style procedural_actions: {json:?}");
        let css = css_for(rules, "https://www.reddit.com/");
        eprintln!("has-in-style css: {css:?}");
        assert!(css.contains(":has(#data-protection-consent-sheet[open])"), "{css}");
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
