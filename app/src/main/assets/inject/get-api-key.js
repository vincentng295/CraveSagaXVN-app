(function () {
    'use strict';

    // Hỗ trợ cả 2 định dạng key:
    //  - Cũ : AIzaSy... (39 ký tự)
    //  - Mới: AQ.Ab8... (gắn với service account ais-gemini-key-...)
    const KEY_REGEX = /AIzaSy[A-Za-z0-9_-]{33}|AQ\.[A-Za-z0-9_-]{30,}/g;
    const KEY_TEST = /^(AIzaSy[A-Za-z0-9_-]{33}|AQ\.[A-Za-z0-9_-]{30,})$/;

    // Phản hồi ListCloudApiKeys là mảng lồng nhau, mỗi key có dạng:
    // [resourceName, displayName, keyString, [ts, nanos], ..., projectName, ..., serviceAccount, ..., uid]
    function extractFromStructure(text) {
        const found = [];
        // Bỏ tiền tố chống XSSI (vd: )]}') nếu có
        const start = text.search(/[\[{]/);
        if (start < 0) return found;
        let data;
        try {
            data = JSON.parse(text.slice(start));
        } catch (e) {
            return found;
        }
        (function walk(node) {
            if (!Array.isArray(node)) return;
            if (
                typeof node[0] === 'string' && node[0].includes('/keys/') &&
                typeof node[2] === 'string' && KEY_TEST.test(node[2])
            ) {
                found.push({
                    name: (typeof node[1] === 'string' && node[1]) ? node[1] : '',
                    key: node[2],
                    project: typeof node[5] === 'string' ? node[5] : ''
                });
                return;
            }
            node.forEach(walk);
        })(data);
        return found;
    }

    function extractKeys(text) {
        let list = extractFromStructure(text);

        // Dự phòng: không parse được cấu trúc thì quét regex
        if (list.length === 0) {
            const matched = text.match(KEY_REGEX) || [];
            list = matched.map(key => ({ name: '', key, project: '' }));
        }

        const seen = new Set();
        const result = [];
        for (const item of list) {
            if (seen.has(item.key)) continue;
            seen.add(item.key);
            result.push({
                name: item.name || `API Key ${result.length + 1}`,
                key: item.key
            });
        }
        return result;
    }

    function handleResponse(url, text) {
        if (!url || !String(url).includes('ListCloudApiKeys')) return;
        try {
            const keys = extractKeys(text);
            if (keys.length === 0) return;

            const urlParams = new URLSearchParams(window.location.search);
            const autoClose = urlParams.get('auto_close') === '1';

            // Bắn data ra ngoài qua window message
            window.postMessage({
                type: 'CRAVE_SAGA_FETCHED_KEYS',
                keys: keys,
                autoClose: autoClose
            }, '*');
        } catch (e) {
            console.error('[GetAPIKey] Lỗi xử lý response:', e);
        }
    }

    // Hook XHR
    const rawOpen = XMLHttpRequest.prototype.open;
    const rawSend = XMLHttpRequest.prototype.send;

    XMLHttpRequest.prototype.open = function (method, url, ...rest) {
        this._url = url;
        return rawOpen.call(this, method, url, ...rest);
    };

    XMLHttpRequest.prototype.send = function (body) {
        this.addEventListener('load', function () {
            try {
                handleResponse(this._url, this.responseText);
            } catch (e) { /* responseType không phải text */ }
        });
        return rawSend.call(this, body);
    };

    // Hook fetch (phòng khi trang chuyển sang dùng fetch)
    const rawFetch = window.fetch;
    if (rawFetch) {
        window.fetch = function (input, init) {
            const url = typeof input === 'string' ? input : (input && input.url);
            const p = rawFetch.apply(this, arguments);
            if (url && String(url).includes('ListCloudApiKeys')) {
                p.then(res => res.clone().text())
                 .then(text => handleResponse(url, text))
                 .catch(() => {});
            }
            return p;
        };
    }

    console.log('[GetAPIKey MAIN] Script hook XHR/fetch đã sẵn sàng.');
})();