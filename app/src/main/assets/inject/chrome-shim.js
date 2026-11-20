// Giả lập tối thiểu các API chrome.* mà extension Crave Saga X VN dùng
// (storage.local, runtime, tabs, downloads) trên nền Android WebView.
// Mọi thứ đi qua đối tượng window.CraveBridge (WebMessageListener) tới phía native.
//
// Quy ước message:
//   JS -> native : chuỗi JSON, hoặc chuỗi "đóng khung" cho dữ liệu lớn:
//                  "#sset\n<header json>\n<payload...>"  (ghi storage)
//                  "#tab\n<json>"                       (tabs.sendMessage)
//   native -> JS : chuỗi JSON {t:'res'|'changed'|'msg'|'fh'|'fc'|'fe'|'ferr', ...}
function __createCraveChrome(opts) {
    'use strict';
    var isPanel = !!(opts && opts.panel);
    var bridge = window.CraveBridge;
    var EXT_BASE = 'https://appassets.androidplatform.net/assets/ext/';
    var fid = Math.random().toString(36).slice(2) + Date.now().toString(36);
    var seq = 0;
    var pending = new Map();
    var fetchStreams = new Map();
    var storageListeners = [];
    var messageListeners = [];
    var lastErr;

    function send(obj) {
        try { bridge.postMessage(typeof obj === 'string' ? obj : JSON.stringify(obj)); }
        catch (e) { console.error('[CraveBridge] gửi thất bại:', e); }
    }

    function request(obj) {
        return new Promise(function (resolve) {
            var id = ++seq;
            pending.set(id, resolve);
            obj.id = id;
            send(obj);
        });
    }

    function cbOrPromise(promise, cb) {
        if (typeof cb === 'function') {
            promise.then(function (v) {
                try { cb(v); } catch (e) { console.error(e); }
            });
            return undefined;
        }
        return promise;
    }

    // ---- base64 helpers ----
    function bytesToB64(buf) {
        var bytes = new Uint8Array(buf);
        var s = '';
        var STEP = 0x8000;
        for (var i = 0; i < bytes.length; i += STEP) {
            s += String.fromCharCode.apply(null, bytes.subarray(i, i + STEP));
        }
        return btoa(s);
    }
    function b64ToBytes(b64) {
        var bin = atob(b64);
        var out = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
        return out;
    }

    // ---- nhận message từ native ----
    function dispatchChanged(changes) {
        var out = {};
        Object.keys(changes).forEach(function (k) {
            out[k] = Object.prototype.hasOwnProperty.call(changes[k], 'newValue')
                ? { newValue: changes[k].newValue } : {};
        });
        storageListeners.slice().forEach(function (fn) {
            try { fn(out, 'local'); } catch (e) { console.error(e); }
        });
    }

    function dispatchMessage(msg) {
        var sender = { id: 'cravesagax-app', tab: { id: 1 } };
        messageListeners.slice().forEach(function (fn) {
            try { fn(msg, sender, function () {}); } catch (e) { console.error(e); }
        });
    }

    function onNative(m) {
        switch (m.t) {
            case 'res': {
                var r = pending.get(m.id);
                if (r) { pending.delete(m.id); r(m); }
                break;
            }
            case 'changed': dispatchChanged(m.changes || {}); break;
            case 'msg': dispatchMessage(m.msg); break;
            case 'fh': {
                var s = fetchStreams.get(m.id);
                if (s && !s.done) {
                    s.done = true;
                    var status = (m.status >= 200 && m.status <= 599) ? m.status : 502;
                    var nullBody = status === 204 || status === 205 || status === 304;
                    s.resolve(new Response(nullBody ? null : s.stream, { status: status }));
                    if (nullBody) fetchStreams.delete(m.id);
                }
                break;
            }
            case 'fc': {
                var sc = fetchStreams.get(m.id);
                if (sc) { try { sc.ctrl.enqueue(b64ToBytes(m.d)); } catch (e) { /* stream đã đóng */ } }
                break;
            }
            case 'fe': {
                var se = fetchStreams.get(m.id);
                if (se) { try { se.ctrl.close(); } catch (e) {} fetchStreams.delete(m.id); }
                break;
            }
            case 'ferr': {
                var sx = fetchStreams.get(m.id);
                if (sx) {
                    fetchStreams.delete(m.id);
                    if (!sx.done) { sx.done = true; sx.reject(new TypeError('Failed to fetch')); }
                    else { try { sx.ctrl.error(new TypeError(m.msg || 'network error')); } catch (e) {} }
                }
                break;
            }
        }
    }

    if (bridge && bridge.addEventListener) {
        bridge.addEventListener('message', function (ev) {
            var m;
            try { m = JSON.parse(ev.data); } catch (e) { return; }
            onNative(m);
        });
    }

    send({ t: 'hello', fid: fid, kind: isPanel ? 'panel' : 'content' });
    window.addEventListener('pagehide', function () { send({ t: 'bye', fid: fid }); });

    // ---- chrome.storage.local ----
    function normalizeKeys(keys) {
        if (keys == null) return { names: null, defaults: {} };
        if (typeof keys === 'string') return { names: [keys], defaults: {} };
        if (Array.isArray(keys)) return { names: keys, defaults: {} };
        return { names: Object.keys(keys), defaults: keys };
    }

    var local = {
        get: function (keys, cb) {
            if (typeof keys === 'function') { cb = keys; keys = null; }
            var k = normalizeKeys(keys);
            var p = request({ t: 'sget', keys: k.names }).then(function (res) {
                var out = {};
                Object.keys(k.defaults).forEach(function (n) { out[n] = k.defaults[n]; });
                var items = res.items || {};
                Object.keys(items).forEach(function (n) { out[n] = items[n]; });
                return out;
            });
            return cbOrPromise(p, cb);
        },
        set: function (items, cb) {
            var keys = [], lens = [], payload = [];
            Object.keys(items || {}).forEach(function (n) {
                if (items[n] === undefined) return;
                var s = JSON.stringify(items[n]);
                if (s === undefined) return;
                keys.push(n); lens.push(s.length); payload.push(s);
            });
            var id = ++seq;
            var p = new Promise(function (resolve) { pending.set(id, resolve); });
            // Đóng khung thay vì JSON.stringify lồng nhau để khỏi escape lại từ điển hàng chục MB.
            send('#sset\n' + JSON.stringify({ id: id, keys: keys, lens: lens }) + '\n' + payload.join(''));
            return cbOrPromise(p.then(function () { return undefined; }), cb);
        },
        remove: function (keys, cb) {
            var names = Array.isArray(keys) ? keys : [keys];
            return cbOrPromise(request({ t: 'srem', keys: names }).then(function () { return undefined; }), cb);
        },
        clear: function (cb) {
            return cbOrPromise(request({ t: 'sclear' }).then(function () { return undefined; }), cb);
        }
    };

    // ---- chrome.tabs / chrome.runtime ----
    var tabs = {
        query: function (info, cb) {
            return cbOrPromise(Promise.resolve([{ id: 1, active: true, url: '' }]), cb);
        },
        sendMessage: function (tabId, message, cb) {
            send('#tab\n' + JSON.stringify(message));
            return cbOrPromise(Promise.resolve(undefined), cb);
        },
        create: function (props, cb) {
            send({ t: 'open', url: String((props && props.url) || '') });
            return cbOrPromise(Promise.resolve({ id: 2 }), cb);
        }
    };

    var runtime = {
        id: 'cravesagax-app',
        getURL: function (path) { return EXT_BASE + String(path || '').replace(/^\//, ''); },
        // App Android không có background/offscreen: không ai nhận message này.
        sendMessage: function (message, cb) {
            if (typeof cb === 'function') {
                setTimeout(function () {
                    lastErr = { message: 'Could not establish connection. Receiving end does not exist.' };
                    try { cb(undefined); } finally { lastErr = undefined; }
                }, 0);
                return undefined;
            }
            return Promise.resolve(undefined);
        },
        onMessage: {
            addListener: function (fn) { messageListeners.push(fn); },
            removeListener: function (fn) {
                var i = messageListeners.indexOf(fn);
                if (i >= 0) messageListeners.splice(i, 1);
            }
        },
        onInstalled: { addListener: function () {} }
    };
    Object.defineProperty(runtime, 'lastError', { get: function () { return lastErr; }, enumerable: true });

    // ---- lưu file (blob:/data:/http) vào Downloads của máy ----
    function saveBlob(blob, filename) {
        var id = ++seq;
        var CHUNK = 768 * 1024; // chia hết cho 3 để base64 không bị đệm giữa chừng
        send({ t: 'fbegin', id: id, name: filename || 'download', mime: blob.type || 'application/octet-stream' });
        var chain = Promise.resolve();
        for (var off = 0; off < blob.size; off += CHUNK) {
            (function (o) {
                chain = chain.then(function () {
                    return blob.slice(o, o + CHUNK).arrayBuffer();
                }).then(function (buf) {
                    send({ t: 'fchunk', id: id, d: bytesToB64(buf) });
                });
            })(off);
        }
        return chain.then(function () { send({ t: 'fend', id: id }); });
    }

    var downloads = {
        download: function (options, cb) {
            var url = options && options.url;
            var name = (options && options.filename) || 'download';
            var p = fetch(url).then(function (r) { return r.blob(); })
                .then(function (b) { return saveBlob(b, name); })
                .then(function () { return 1; });
            return cbOrPromise(p, cb);
        }
    };

    // Android WebView không tải được blob: qua <a download>. Chặn click và đẩy sang native.
    var rawAnchorClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        var href = this.href || '';
        if (this.hasAttribute('download') && /^(blob:|data:)/.test(href)) {
            var name = this.getAttribute('download') || 'download';
            fetch(href).then(function (r) { return r.blob(); })
                .then(function (b) { return saveBlob(b, name); })
                .catch(function (e) { console.error('[CraveApp] lưu file lỗi:', e); });
            return;
        }
        return rawAnchorClick.apply(this, arguments);
    };

    // ---- fetch qua native (bỏ qua CORS/CSP của trang) cho Gemini/Gemma API ----
    var rawFetch = window.fetch.bind(window);

    function nativeFetch(url, init) {
        init = init || {};
        return new Promise(function (resolve, reject) {
            var id = ++seq;
            var ctrl;
            var stream = new ReadableStream({
                start: function (c) { ctrl = c; },
                cancel: function () { send({ t: 'fcancel', id: id }); fetchStreams.delete(id); }
            });
            fetchStreams.set(id, { ctrl: ctrl, stream: stream, resolve: resolve, reject: reject, done: false });
            var headers = {};
            if (init.headers) {
                if (typeof Headers !== 'undefined' && init.headers instanceof Headers) {
                    init.headers.forEach(function (v, k) { headers[k] = v; });
                } else {
                    Object.keys(init.headers).forEach(function (k) { headers[k] = init.headers[k]; });
                }
            }
            send({
                t: 'fetch', id: id, url: String(url), method: init.method || 'GET',
                headers: headers, body: init.body == null ? null : String(init.body)
            });
        });
    }

    function smartFetch(input, init) {
        var url = (typeof input === 'string') ? input : (input && input.url) || String(input);
        if (/^https:\/\/generativelanguage\.googleapis\.com\//.test(url)) {
            return nativeFetch(url, init);
        }
        return rawFetch(input, init);
    }

    return {
        storage: {
            local: local,
            onChanged: {
                addListener: function (fn) { storageListeners.push(fn); },
                removeListener: function (fn) {
                    var i = storageListeners.indexOf(fn);
                    if (i >= 0) storageListeners.splice(i, 1);
                }
            }
        },
        runtime: runtime,
        tabs: tabs,
        downloads: downloads,
        // phần mở rộng riêng của app
        __fetch: smartFetch,
        __app: function (action) { send({ t: 'app', action: action }); },
        // Gửi message thô tới native (vd: dịch Gemma chạy nền: gemma_start / gemma_cancel / gemma_status).
        __native: function (obj) { send(obj); },
        __notify: function (kind, text) { send({ t: 'notify', kind: String(kind || ''), text: String(text || '') }); }
    };
}
