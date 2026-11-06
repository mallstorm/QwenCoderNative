(function () {
  if (window.__QC && window.__QC.v === 3) return;
  var Q = { v: 3, _ch: {} };

  function C() { return window.__QC_CFG || {}; }
  function arr(l) { return Array.prototype.slice.call(l); }
  function q(sel, root) { try { return arr((root || document).querySelectorAll(sel)); } catch (e) { return []; } }
  function vis(e) {
    if (!e || !e.getClientRects || e.getClientRects().length === 0) return false;
    var s = getComputedStyle(e);
    return s.visibility !== 'hidden' && s.display !== 'none';
  }
  function txt(e) { return (e.innerText || e.textContent || '').trim(); }
  function attrs(e) {
    return ((e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '') + ' ' +
      (e.getAttribute('data-testid') || '') + ' ' + (typeof e.className === 'string' ? e.className : '') + ' ' +
      (e.id || '')).toLowerCase();
  }
  function dec(b64) {
    return new TextDecoder('utf-8').decode(Uint8Array.from(atob(b64), function (c) { return c.charCodeAt(0); }));
  }
  function clean(s) { return s.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim(); }

  // ---------- input / send / stop ----------
  Q.input = function () {
    var cfg = C();
    if (cfg.input) { var cc = q(cfg.input).filter(vis); if (cc.length) return cc[cc.length - 1]; }
    var c = q('textarea,[contenteditable="true"],[contenteditable=""],[role="textbox"],input[type="text"],input:not([type])')
      .filter(vis).filter(function (e) { return !e.readOnly && !e.disabled; });
    if (!c.length) return null;
    var act = document.activeElement;
    if (c.indexOf(act) >= 0) return act;
    c.sort(function (a, b) { return b.getBoundingClientRect().bottom - a.getBoundingClientRect().bottom; });
    return c[0];
  };

  function setText(el, text) {
    el.focus();
    if (el.isContentEditable) {
      var sel = window.getSelection();
      var r = document.createRange();
      r.selectNodeContents(el);
      sel.removeAllRanges();
      sel.addRange(r);
      var ok = false;
      try { ok = document.execCommand('insertText', false, text); } catch (e) {}
      if (!ok) { el.textContent = text; }
      el.dispatchEvent(new InputEvent('input', { bubbles: true, data: text, inputType: 'insertText' }));
    } else {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, text);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  Q.sendBtn = function (inp) {
    var cfg = C();
    if (cfg.send) { var cc = q(cfg.send).filter(vis); if (cc.length) return cc[cc.length - 1]; }
    var scope = inp;
    for (var i = 0; i < 6 && scope && scope.parentElement; i++) {
      scope = scope.parentElement;
      var btns = q('button,[role="button"]', scope).filter(vis);
      var by = btns.filter(function (b) { return /send|submit|发送|отправ/.test(attrs(b)) || b.type === 'submit'; });
      if (by.length) return by[by.length - 1];
      var icon = btns.filter(function (b) {
        return b.querySelector('svg,img') && !txt(b) &&
          !/stop|attach|upload|mic|voice|file|image|clip|停止|附件|上传/.test(attrs(b));
      });
      if (icon.length) return icon[icon.length - 1];
    }
    return null;
  };

  Q.stopBtn = function () {
    var cfg = C();
    if (cfg.stop) { var cc = q(cfg.stop).filter(vis); if (cc.length) return cc[0]; }
    var all = q('button,[role="button"]').filter(vis);
    for (var i = all.length - 1; i >= 0; i--) {
      var s = attrs(all[i]) + ' ' + txt(all[i]).toLowerCase();
      if (txt(all[i]).length < 24 && /stop|停止|остановить|cancel generat/.test(s)) return all[i];
    }
    return null;
  };

  Q.send = function (b64) {
    var text = dec(b64);
    var inp = Q.input();
    if (!inp) return 'noinput';
    setText(inp, text);
    setTimeout(function () {
      var b = Q.sendBtn(inp);
      if (b && !b.disabled && b.getAttribute('aria-disabled') !== 'true') {
        b.click();
      } else {
        var o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
        inp.dispatchEvent(new KeyboardEvent('keydown', o));
        inp.dispatchEvent(new KeyboardEvent('keypress', o));
        inp.dispatchEvent(new KeyboardEvent('keyup', o));
      }
    }, 250);
    return 'ok';
  };

  Q.stop = function () { var b = Q.stopBtn(); if (b) { b.click(); return 'ok'; } return 'none'; };

  Q.newChat = function () {
    var all = q('a,button,[role="button"]').filter(vis);
    for (var i = 0; i < all.length; i++) {
      var s = (attrs(all[i]) + ' ' + txt(all[i])).toLowerCase();
      if (txt(all[i]).length < 30 && /new chat|new task|new session|新对话|新建|новый чат|новая задача/.test(s)) {
        all[i].click();
        return 'ok';
      }
    }
    return 'none';
  };

  // ---------- files ----------
  Q.chunkStart = function (id) { Q._ch[id] = []; };
  Q.chunk = function (id, p) { Q._ch[id].push(p); };
  Q.attachFile = function (id, nameB64, mime) {
    var b64 = (Q._ch[id] || []).join('');
    delete Q._ch[id];
    var bin = atob(b64);
    var u = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
    var f = new File([u], dec(nameB64), { type: mime });
    var inputs = q('input[type="file"]');
    if (!inputs.length) return 'nofile';
    var target = inputs.filter(function (x) { return !x.accept || x.accept.indexOf('*') >= 0; })[0] || inputs[0];
    var dt = new DataTransfer();
    dt.items.add(f);
    target.files = dt.files;
    target.dispatchEvent(new Event('input', { bubbles: true }));
    target.dispatchEvent(new Event('change', { bubbles: true }));
    return 'ok';
  };

  // ---------- messages ----------
  var MSG_SEL = '[data-message-id],[data-role],[data-author],[data-message-author-role],' +
    '[class*="message" i],[class*="chat-item" i],[class*="bubble" i],[class*="markdown" i],' +
    '[class*="response" i],[class*="query" i]';

  function roleOf(e) {
    var a = (e.getAttribute('data-role') || '') + ' ' + (e.getAttribute('data-message-author-role') || '') + ' ' +
      (e.getAttribute('data-author') || '') + ' ' + attrs(e);
    if (/(^|[^a-z])(user|human|query|question|self|mine|you)([^a-z]|$)/.test(a)) return 'user';
    if (/assistant|bot|qwen|answer|response|reply|markdown|model|(^|[^a-z])ai([^a-z]|$)/.test(a)) return 'ai';
    return '';
  }

  function md(n) {
    if (n.nodeType === 3) return n.nodeValue;
    if (n.nodeType !== 1) return '';
    var t = n.tagName.toLowerCase();
    if (/^(script|style|svg|button|noscript)$/.test(t)) return '';
    if (n.getAttribute && n.getAttribute('aria-hidden') === 'true') return '';
    if (t === 'pre') {
      var c = n.querySelector('code');
      var lang = '';
      if (c) { var m = /language-([a-z0-9+#-]+)/i.exec(c.className || ''); if (m) lang = m[1]; }
      return '\n```' + lang + '\n' + ((c || n).textContent || '').replace(/\n+$/, '') + '\n```\n';
    }
    if (t === 'br') return '\n';
    var inner = '';
    for (var k = n.firstChild; k; k = k.nextSibling) inner += md(k);
    if (t === 'code') return '`' + inner + '`';
    if (t === 'strong' || t === 'b') return '**' + inner + '**';
    if (/^h[1-6]$/.test(t)) return '\n' + new Array(+t.charAt(1) + 1).join('#') + ' ' + inner.trim() + '\n';
    if (t === 'li') return '\n- ' + inner.trim();
    if (t === 'p' || t === 'div' || t === 'ul' || t === 'ol' || t === 'table' || t === 'tr' || t === 'section') return '\n' + inner + '\n';
    if (t === 'td' || t === 'th') return inner.trim() + ' | ';
    return inner;
  }

  Q.collect = function () {
    var cfg = C();
    var inp = Q.input();
    var els = q(cfg.msg || MSG_SEL).filter(vis).filter(function (e) {
      if (inp && (e.contains(inp) || inp.contains(e))) return false;
      if (e.closest && e.closest('nav,header,aside,footer,button,[role="button"],[role="navigation"],[role="menu"],[role="dialog"]')) return false;
      return txt(e).length > 0;
    });
    var set = els.slice();
    els = els.filter(function (e) {
      var n = 0;
      for (var i = 0; i < set.length; i++) { if (set[i] !== e && e.contains(set[i])) n++; }
      return n < 2;
    });
    set = els.slice();
    els = els.filter(function (e) {
      for (var i = 0; i < set.length; i++) { if (set[i] !== e && set[i].contains(e)) return false; }
      return true;
    });
    var W = window.innerWidth || 1;
    return els.map(function (e) {
      var r = roleOf(e);
      if (!r) { var b = e.getBoundingClientRect(); r = (b.left + b.width / 2) > W * 0.6 ? 'user' : 'ai'; }
      return { r: r, t: clean(md(e)) };
    }).filter(function (m) { return m.t.length > 0; });
  };

  Q.snapshot = function () {
    var inp = Q.input();
    var login = q('input[type="password"],input[type="email"]').filter(vis).length > 0 ||
      /\/(auth|login|signin)/i.test(location.pathname);
    return JSON.stringify({
      url: location.href,
      title: document.title,
      login: login,
      hasInput: !!inp,
      busy: !!Q.stopBtn(),
      msgs: Q.collect()
    });
  };

  // ---------- diagnostics ----------
  Q.dump = function () {
    var out = [];
    function walk(e, d) {
      if (d > 14 || out.length > 700) return;
      if (d > 0 && !vis(e)) return;
      var cn = (typeof e.className === 'string' ? e.className : '').slice(0, 70).trim();
      var line = new Array(d + 1).join(' ') + e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') +
        (cn ? '.' + cn.replace(/\s+/g, '.') : '');
      ['role', 'aria-label', 'data-role', 'data-testid', 'type', 'placeholder'].forEach(function (a) {
        var v = e.getAttribute(a);
        if (v) line += ' [' + a + '=' + v.slice(0, 30) + ']';
      });
      var own = '';
      for (var k = e.firstChild; k; k = k.nextSibling) if (k.nodeType === 3) own += k.nodeValue;
      own = own.trim();
      if (own) line += ' "' + own.slice(0, 40) + '"';
      out.push(line);
      for (var c = e.firstElementChild; c; c = c.nextElementSibling) walk(c, d + 1);
    }
    walk(document.body, 0);
    return location.href + '\n' + out.join('\n');
  };

  window.__QC = Q;
})();
