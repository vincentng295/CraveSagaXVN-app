package vn.huskydg.cravesagax

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject

/**
 * Đăng nhập kiểu "chỉ hiện form" + mở liên kết ngoài bằng trình duyệt máy
 * cho các phiên bản có [Game.login] (Nutaku, Erolabs).
 */
object LoginHelper {

    private fun origin(url: String): String {
        val u = Uri.parse(url)
        val port = if (u.port != -1) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$port"
    }

    private fun trimPath(p: String?) = (p ?: "").trimEnd('/')

    fun isLoginPage(game: Game, url: String): Boolean {
        val flow = game.login ?: return false
        val u = Uri.parse(url)
        val l = Uri.parse(flow.url)
        return u.host.equals(l.host, true) && trimPath(u.path) == trimPath(l.path)
    }

    fun isGamePage(game: Game, url: String): Boolean {
        val u = Uri.parse(url)
        val g = Uri.parse(game.url)
        return u.host.equals(g.host, true) && trimPath(u.path).startsWith(trimPath(g.path))
    }

    /** Gắn script "chỉ hiện form" (chạy ngay đầu trang, không bị nháy) và chặn liên kết ngoài. */
    fun install(wv: WebView, game: Game) {
        val flow = game.login ?: return
        val loginOrigins = setOf(origin(flow.url))
        val linkOrigins = setOf(origin(flow.url), origin(game.url))

        val cfg = JSONObject()
            .put("host", Uri.parse(flow.url).host)
            .put("path", trimPath(Uri.parse(flow.url).path))
            .put("sel", flow.formSelector)
        WebViewCompat.addDocumentStartJavaScript(wv, "($FORM_ONLY_JS)($cfg);", loginOrigins)

        WebViewCompat.addWebMessageListener(wv, "CraveExternal", linkOrigins) { view, message: WebMessageCompat, _, _, _ ->
            openExternal(view.context, message.data)
        }
        WebViewCompat.addDocumentStartJavaScript(wv, LINKS_JS, linkOrigins)
    }

    private fun openExternal(ctx: Context, raw: String?) {
        val uri = try { Uri.parse(raw ?: return) } catch (_: Exception) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        if (uri.host.isNullOrEmpty()) return
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    /** Mọi thẻ <a href> (http/https, không phải #/javascript:) → mở ngoài app. */
    private const val LINKS_JS = """
(function(){
  document.addEventListener('click', function(e){
    if (!window.CraveExternal) return;
    var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
    if (!a) return;
    var raw = a.getAttribute('href') || '';
    if (!raw || raw.charAt(0) === '#' || /^\s*javascript:/i.test(raw)) return;
    var u; try { u = new URL(a.href, location.href); } catch (_) { return; }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') return;
    e.preventDefault(); e.stopImmediatePropagation();
    window.CraveExternal.postMessage(u.href);
  }, true);
})();
"""

    /**
     * Chỉ giữ lại phần tử cfg.sel: ẩn mọi phần tử "anh em" dọc theo chuỗi cha của nó (các cha vẫn hiện
     * để form còn nằm trong DOM). Trang bị ẩn tới khi tìm thấy form; quá 12s không thấy thì trả lại trang gốc.
     * Giữ lại iframe captcha (reCAPTCHA / hCaptcha / Cloudflare) để thử thách không bị ẩn.
     */
    private const val FORM_ONLY_JS = """
function(cfg){
  try {
    var norm = function(p){ return (p || '').replace(/\/+$/, ''); };
    if (location.hostname !== cfg.host || norm(location.pathname) !== cfg.path) return;
    if (window.__csxFormOnly) return; window.__csxFormOnly = true;
    var KEEP = 'data-csx-keep', HIDE = 'data-csx-hide', TGT = 'data-csx-target';
    var root = document.documentElement;
    root.classList.add('csx-wait');
    var st = document.createElement('style');
    st.textContent =
      'html{background:#0f1220!important;overflow:auto!important;height:auto!important}' +
      'body{background:#0f1220!important;margin:0!important;padding:12px!important;overflow:auto!important;height:auto!important;min-height:100vh!important;box-sizing:border-box!important}' +
      'html.csx-wait body{visibility:hidden!important}' +
      '[' + HIDE + ']{display:none!important}' +
      '[' + KEEP + ']{position:static!important;transform:none!important;width:auto!important;max-width:none!important;height:auto!important;min-height:0!important;max-height:none!important;overflow:visible!important;opacity:1!important;visibility:visible!important;margin:0!important;padding:0!important;border:0!important;background:none!important;box-shadow:none!important;float:none!important}' +
      '[' + TGT + ']{position:relative!important;width:92%!important;max-width:460px!important;margin:12px auto!important;opacity:1!important;visibility:visible!important;transform:none!important;box-sizing:border-box!important}';
    root.appendChild(st);
    var SKIP = {SCRIPT:1, STYLE:1, LINK:1, META:1, NOSCRIPT:1, TEMPLATE:1};
    var CAPTCHA = 'iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="challenges.cloudflare"],iframe[title*="recaptcha"]';
    function show(el){
      if (getComputedStyle(el).display === 'none') el.style.setProperty('display', 'block', 'important');
    }
    function apply(){
      var t = document.querySelector(cfg.sel);
      if (!t || !document.body) return false;
      t.setAttribute(TGT, '1'); show(t);
      var n = t;
      while (n && n !== document.body) {
        var p = n.parentElement; if (!p) break;
        Array.prototype.forEach.call(p.children, function(c){
          if (c === n || SKIP[c.tagName]) return;
          var captcha = c.tagName === 'IFRAME' ? /recaptcha|hcaptcha|challenges\.cloudflare/.test(c.src || '') : !!c.querySelector(CAPTCHA);
          if (captcha) c.removeAttribute(HIDE); else c.setAttribute(HIDE, '1');
        });
        if (n !== t) { n.setAttribute(KEEP, '1'); show(n); }
        n = p;
      }
      root.classList.remove('csx-wait');
      return true;
    }
    var queued = false;
    function schedule(){
      if (queued) return; queued = true;
      requestAnimationFrame(function(){ queued = false; apply(); });
    }
    new MutationObserver(schedule).observe(root, {childList: true, subtree: true});
    document.addEventListener('DOMContentLoaded', schedule);
    schedule();
    setTimeout(function(){ root.classList.remove('csx-wait'); }, 12000);
  } catch (e) { try { document.documentElement.classList.remove('csx-wait'); } catch (_) {} }
}
"""
}
