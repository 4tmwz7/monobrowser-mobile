package com.voluene.monobrowser.adblock

/**
 * JavaScript used for cosmetic filtering. Pure string builders (unit-tested for escaping).
 *
 * Styles are applied through constructable stylesheets (`adoptedStyleSheets`) when available, because
 * they are not subject to the page's CSP `style-src`; a `<style>` element is the fallback.
 */
object CosmeticScript {
    const val BRIDGE_NAME = "MonoAdblock"

    /** JS string literal (double-quoted) safe to embed in a script, including inside HTML. */
    fun jsString(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                '<' -> sb.append("\\u003c")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    /** One-shot snippet for per-navigation injection (fallback when document-start scripts are unsupported). */
    fun inject(css: String): String =
        "(function(){try{var css=${jsString(css)};var s=window.__monoAdblockSheet;" +
            "if(!s&&window.CSSStyleSheet&&document.adoptedStyleSheets!==undefined){try{s=new CSSStyleSheet();" +
            "document.adoptedStyleSheets=document.adoptedStyleSheets.concat([s]);window.__monoAdblockSheet=s;}catch(e){s=null;}}" +
            "if(s&&s.replaceSync){s.replaceSync(css);return;}" +
            "var st=document.getElementById('__mono_adblock_css');if(!st){st=document.createElement('style');st.id='__mono_adblock_css';" +
            "(document.head||document.documentElement).appendChild(st);}st.textContent=css;}catch(e){}})();"

    /**
     * Static document-start script (runs in every frame before page scripts). Asks the native bridge
     * (`window.MonoAdblock`, see [RustAdBlocker]) for hostname CSS, then keeps asking for generic
     * class/id selectors as the DOM grows. The bridge returns "" when blocking is off for the site.
     */
    val DOCUMENT_START: String = """
(function(){
  try {
    var b = window.$BRIDGE_NAME;
    if (!b || window.__monoAdblock) return;
    var href = location.href;
    if (!/^https?:/i.test(href)) return;
    window.__monoAdblock = true;
    var sheet = null, fallback = null, sheetText = '';
    function apply(css) {
      if (!css) return;
      try {
        if (!sheet && window.CSSStyleSheet && document.adoptedStyleSheets !== undefined) {
          sheet = new CSSStyleSheet();
          document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
        }
        if (sheet) { sheetText += css; sheet.replaceSync(sheetText); return; }
      } catch (e) { sheet = null; }
      if (!fallback) {
        fallback = document.createElement('style');
        (document.head || document.documentElement).appendChild(fallback);
      }
      fallback.appendChild(document.createTextNode(css));
    }
    apply(b.cosmeticCss(href));
    var seenC = Object.create(null), seenI = Object.create(null), pending = [], timer = 0;
    function scan(list) {
      var cs = [], is = [];
      function visit(el) {
        if (el.id && !seenI[el.id]) { seenI[el.id] = 1; is.push(el.id); }
        var cl = el.classList;
        if (cl) for (var i = 0; i < cl.length; i++) { var c = cl[i]; if (!seenC[c]) { seenC[c] = 1; cs.push(c); } }
      }
      for (var k = 0; k < list.length; k++) {
        var n = list[k];
        if (n.nodeType !== 1) continue;
        visit(n);
        var all = n.querySelectorAll ? n.querySelectorAll('[class],[id]') : [];
        for (var j = 0; j < all.length; j++) visit(all[j]);
      }
      if (cs.length || is.length) apply(b.genericCss(href, cs.join('\n'), is.join('\n')));
    }
    function flush() { timer = 0; var l = pending; pending = []; scan(l); }
    function queue(n) { pending.push(n); if (!timer) timer = setTimeout(flush, 250); }
    function start() {
      var root = document.documentElement;
      if (!root) return false;
      queue(root);
      new MutationObserver(function (ms) {
        for (var i = 0; i < ms.length; i++) {
          var m = ms[i];
          if (m.type === 'attributes') queue(m.target);
          else for (var j = 0; j < m.addedNodes.length; j++) queue(m.addedNodes[j]);
        }
      }).observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
      return true;
    }
    if (!start()) document.addEventListener('DOMContentLoaded', start, { once: true });
  } catch (e) {}
})();
""".trimIndent()
}
