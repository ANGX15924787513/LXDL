/* LX 音源宿主 shim：跑在 QuickJS 里，不是浏览器环境。
   对外暴露 globalThis.lx（洛雪脚本 API），底层通过 __host* 绑到 Kotlin。 */
(function () {
  'use strict';
  var g = globalThis;
  g.window = g;
  g.self = g;
  g.global = g;

  function toStr(v) {
    try { return String(v == null ? '' : v); } catch (e) { return ''; }
  }
  function errText(e) {
    if (e == null) return 'unknown error';
    if (typeof e === 'string') return e;
    if (e.message != null) return toStr(e.message);
    try { return JSON.stringify(e); } catch (_) { return toStr(e); }
  }
  function toJson(v) {
    try { var s = JSON.stringify(v); return s === undefined ? 'null' : s; }
    catch (e) { return JSON.stringify({ error: errText(e) }); }
  }

  // ---------------- base64 ----------------
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  function btoaImpl(bin) {
    var out = '', i, c1, c2, c3;
    for (i = 0; i < bin.length; i += 3) {
      c1 = bin.charCodeAt(i);
      c2 = i + 1 < bin.length ? bin.charCodeAt(i + 1) : NaN;
      c3 = i + 2 < bin.length ? bin.charCodeAt(i + 2) : NaN;
      out += B64.charAt(c1 >> 2);
      out += B64.charAt(((c1 & 3) << 4) | (isNaN(c2) ? 0 : c2 >> 4));
      out += isNaN(c2) ? '=' : B64.charAt(((c2 & 15) << 2) | (isNaN(c3) ? 0 : c3 >> 6));
      out += isNaN(c3) ? '=' : B64.charAt(c3 & 63);
    }
    return out;
  }
  function atobImpl(b64) {
    var s = toStr(b64).replace(/[^A-Za-z0-9+/=]/g, '');
    var out = '', i = 0, b1, b2, b3, b4, n;
    while (i < s.length) {
      b1 = B64.indexOf(s.charAt(i++));
      b2 = B64.indexOf(s.charAt(i++));
      b3 = B64.indexOf(s.charAt(i++));
      b4 = B64.indexOf(s.charAt(i++));
      n = (b1 << 18) | (b2 << 12) | ((b3 & 63) << 6) | (b4 & 63);
      out += String.fromCharCode((n >> 16) & 255);
      if (b3 !== 64 && b3 !== -1) out += String.fromCharCode((n >> 8) & 255);
      if (b4 !== 64 && b4 !== -1) out += String.fromCharCode(n & 255);
    }
    return out;
  }
  g.atob = g.atob || atobImpl;
  g.btoa = g.btoa || btoaImpl;

  // ---------------- UTF-8 ----------------
  function utf8Encode(str) {
    var s = toStr(str), out = [], i, c, c2, cp;
    for (i = 0; i < s.length; i++) {
      c = s.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xC0 | (c >> 6), 0x80 | (c & 63));
      else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
        c2 = s.charCodeAt(++i);
        cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
        out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      } else out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
    }
    return new Uint8Array(out);
  }
  function utf8Decode(bytes) {
    var b = Buf.from(bytes), out = '', i = 0, c, c2, c3, cp;
    while (i < b.length) {
      c = b[i++];
      if (c < 0x80) out += String.fromCharCode(c);
      else if (c >= 0xC0 && c < 0xE0) {
        c2 = b[i++] & 63;
        out += String.fromCharCode(((c & 31) << 6) | c2);
      } else if (c >= 0xE0 && c < 0xF0) {
        c2 = b[i++] & 63; c3 = b[i++] & 63;
        out += String.fromCharCode(((c & 15) << 12) | (c2 << 6) | c3);
      } else {
        c2 = b[i++] & 63; c3 = b[i++] & 63;
        cp = ((c & 7) << 18) | (c2 << 12) | (c3 << 6) | (b[i++] & 63);
        cp -= 0x10000;
        out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 1023));
      }
    }
    return out;
  }
  if (typeof g.TextEncoder === 'undefined') {
    g.TextEncoder = function () {};
    g.TextEncoder.prototype.encode = function (s) { return utf8Encode(s); };
  }
  if (typeof g.TextDecoder === 'undefined') {
    g.TextDecoder = function () {};
    g.TextDecoder.prototype.decode = function (b) { return b == null ? '' : utf8Decode(b); };
  }
  var dec = new g.TextDecoder();
  var enc = new g.TextEncoder();

  // ---------------- Buffer ----------------
  function Buf(u8) { return u8; }
  Buf.from = function (v, encoding) {
    if (typeof v === 'string') {
      var e = toStr(encoding || 'utf8').toLowerCase();
      if (e === 'base64') return b64ToBytes(v);
      if (e === 'hex') {
        var out = new Uint8Array(Math.floor(v.length / 2));
        for (var i = 0; i < out.length; i++) out[i] = parseInt(v.substr(i * 2, 2), 16);
        return out;
      }
      return utf8Encode(v);
    }
    if (v instanceof Uint8Array) return v;
    if (v instanceof ArrayBuffer) return new Uint8Array(v);
    if (Array.isArray(v)) return new Uint8Array(v);
    return new Uint8Array(0);
  };
  Buf.isBuffer = function (v) { return v instanceof Uint8Array; };
  Buf.concat = function (list) {
    var n = 0, i;
    for (i = 0; i < list.length; i++) n += list[i].length;
    var out = new Uint8Array(n), off = 0;
    for (i = 0; i < list.length; i++) { out.set(list[i], off); off += list[i].length; }
    return out;
  };
  function b64ToBytes(s) {
    var bin = g.atob(toStr(s || ''));
    var out = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }
  function bytesToB64(bytes) {
    var s = '';
    for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return g.btoa(s);
  }
  function bufToString(buf, fmt) {
    var b = Buf.from(buf);
    var f = toStr(fmt || 'utf8').toLowerCase();
    if (f === 'base64') return bytesToB64(b);
    if (f === 'hex') {
      var s = '';
      for (var i = 0; i < b.length; i++) s += (b[i] < 16 ? '0' : '') + b[i].toString(16);
      return s;
    }
    return dec.decode(b);
  }
  // 让 Uint8Array 用起来像 Node 的 Buffer
  Uint8Array.prototype.toString = function (encoding) {
    return bufToString(this, encoding || 'utf8');
  };
  g.Buffer = {
    from: Buf.from,
    isBuffer: Buf.isBuffer,
    concat: Buf.concat,
    alloc: function (n) { return new Uint8Array(Number(n) || 0); }
  };

  // ---------------- 定时器 ----------------
  var timerSeq = 1;
  var timers = {};
  g.setTimeout = function (fn, ms) {
    var id = timerSeq++;
    if (typeof fn !== 'function') return id;
    timers[id] = fn;
    __hostSetTimeout(id, Math.max(0, Number(ms) || 0));
    return id;
  };
  g.clearTimeout = function (id) {
    delete timers[id];
    runCatchingClear(id);
  };
  function runCatchingClear(id) {
    try { __hostClearTimeout(id); } catch (e) {}
  }
  g.__fireTimer = function (id) {
    var fn = timers[id];
    delete timers[id];
    if (typeof fn === 'function') {
      try { fn(); } catch (e) { try { __hostLog('error', errText(e)); } catch (_) {} }
    }
  };
  g.setInterval = g.setTimeout;

  // ---------------- console ----------------
  if (!g.console) g.console = {};
  ['log', 'info', 'warn', 'error'].forEach(function (level) {
    g.console[level] = function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) parts.push(errText(arguments[i]));
      try { __hostLog(level, parts.join(' ')); } catch (e) {}
    };
  });

  // ---------------- 请求 ----------------
  function normOptions(o) {
    o = o || {};
    var out = {
      method: toStr(o.method || 'GET').toUpperCase(),
      headers: {},
      timeout: Number(o.timeout) || 20000,
      responseType: o.responseType || 'text',
      body: null,
      bodyEncoding: 'text'
    };
    if (o.headers) Object.keys(o.headers).forEach(function (k) {
      if (o.headers[k] != null) out.headers[k] = toStr(o.headers[k]);
    });
    function urlenc(obj) {
      return Object.keys(obj).map(function (k) {
        return encodeURIComponent(k) + '=' + encodeURIComponent(obj[k] == null ? '' : toStr(obj[k]));
      }).join('&');
    }
    function formHeaders() {
      if (!out.headers['Content-Type'] && !out.headers['content-type']) {
        out.headers['Content-Type'] = 'application/x-www-form-urlencoded';
      }
    }
    if (o.form) { out.body = urlenc(o.form); out.bodyEncoding = 'text'; formHeaders(); }
    if (o.formData) { out.body = urlenc(o.formData); out.bodyEncoding = 'text'; formHeaders(); }
    if (o.body != null) {
      if (typeof o.body === 'string') { out.body = o.body; out.bodyEncoding = 'text'; }
      else if (o.body instanceof Uint8Array || o.body instanceof ArrayBuffer || Array.isArray(o.body)) {
        out.body = bytesToB64(Buf.from(o.body));
        out.bodyEncoding = 'base64';
      } else {
        out.body = urlenc(o.body);
        out.bodyEncoding = 'text';
        formHeaders();
      }
    }
    return JSON.stringify(out);
  }

  function makeResponse(raw) {
    var r = JSON.parse(raw);
    var body = r.binary ? b64ToBytes(r.body).buffer : dec.decode(b64ToBytes(r.body));
    return {
      status: r.status,
      statusText: r.statusText || '',
      headers: r.headers || {},
      body: body,
      raw: body
    };
  }

  // 部分脚本直接用 fetch，这里给个最小实现
  if (typeof g.fetch === 'undefined') {
    g.fetch = function (url, options) {
      var opts = normOptions(options || {});
      return __hostFetch(toStr(url), opts).then(function (raw) {
        var r = JSON.parse(raw);
        var body = r.binary ? b64ToBytes(r.body) : dec.decode(b64ToBytes(r.body));
        return {
          ok: r.status >= 200 && r.status < 300,
          status: r.status,
          statusText: r.statusText || '',
          headers: {
            get: function (k) {
              var h = r.headers || {};
              return h[toStr(k).toLowerCase()] || null;
            }
          },
          text: function () {
            return Promise.resolve(typeof body === 'string' ? body : dec.decode(body));
          },
          json: function () {
            return Promise.resolve(JSON.parse(typeof body === 'string' ? body : dec.decode(body)));
          },
          arrayBuffer: function () {
            return Promise.resolve((body instanceof Uint8Array ? body : utf8Encode(body)).buffer);
          }
        };
      });
    };
  }

  // ---------------- lx API ----------------
  var handlers = {};
  var state = { inited: null, alert: null };

  var lx = {
    version: '2.0.0',
    env: 'mobile',
    EVENT_NAMES: { request: 'request', inited: 'inited', updateAlert: 'updateAlert' },
    on: function (name, fn) { handlers[toStr(name)] = fn; },
    send: function (name, data) {
      name = toStr(name);
      if (name === 'inited') state.inited = data;
      if (name === 'updateAlert') state.alert = data;
      try { __hostSend(name, toJson(data)); } catch (e) {}
    },
    request: function (url, options, cb) {
      if (typeof options === 'function') { cb = options; options = {}; }
      var opts;
      try { opts = normOptions(options); } catch (e) {
        if (cb) { try { cb(e, null); } catch (_) {} }
        return function () {};
      }
      __hostFetch(toStr(url), opts).then(function (raw) {
        var resp;
        try { resp = makeResponse(raw); } catch (e) {
          if (cb) { try { cb(e, null); } catch (_) {} }
          return;
        }
        if (cb) { try { cb(null, resp); } catch (e) { try { __hostLog('error', errText(e)); } catch (_) {} } }
      }, function (e) {
        if (cb) { try { cb(new Error(errText(e)), null); } catch (_) {} }
      });
      return function () {};
    },
    utils: {
      buffer: { from: Buf.from, bufToString: bufToString },
      crypto: {
        md5: function (v) { return __hostMd5(typeof v === 'string' ? v : bufToString(v)); },
        aesEncrypt: function (data, mode, key, iv) {
          var out = __hostAes(
            bytesToB64(Buf.from(data)), toStr(mode),
            bytesToB64(Buf.from(key)), iv == null ? '' : bytesToB64(Buf.from(iv))
          );
          if (out.indexOf('\u0001ERR:') === 0) throw new Error(out.slice(6));
          return b64ToBytes(out);
        },
        rsaEncrypt: function (data, pem) {
          var out = __hostRsa(bytesToB64(Buf.from(data)), toStr(pem));
          if (out.indexOf('\u0001ERR:') === 0) throw new Error(out.slice(6));
          return b64ToBytes(out);
        },
        randomBytes: function (n) { return b64ToBytes(__hostRandom(Number(n) || 0)); }
      }
    }
  };
  Object.defineProperty(lx, 'currentScriptInfo', {
    get: function () { return g.__lxScriptInfo || {}; }
  });
  g.lx = lx;

  // ---------------- Kotlin 侧入口 ----------------
  g.__lxAction = function (id, source, action, infoJson) {
    var info;
    try { info = JSON.parse(infoJson || '{}'); } catch (e) { info = {}; }
    var fn = handlers['request'];
    if (typeof fn !== 'function') {
      __hostSend('__result', JSON.stringify({ id: id, ok: false, error: '脚本没有注册 request 事件' }));
      return;
    }
    Promise.resolve().then(function () {
      return fn({ source: source, action: action, info: info });
    }).then(function (v) {
      __hostSend('__result', JSON.stringify({ id: id, ok: true, value: v === undefined ? null : v }));
    }, function (e) {
      __hostSend('__result', JSON.stringify({ id: id, ok: false, error: errText(e) }));
    });
  };

  g.__lxState = function () { return toJson(state); };
  g.__lxReady = true;
})();
