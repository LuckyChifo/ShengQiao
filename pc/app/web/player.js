(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var now = function () { return performance.now(); };
  var params = new URLSearchParams(location.search);
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('sq.' + k); return v === null ? d : v; } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('sq.' + k, v); } catch (e) {} },
    remove: function (k) { try { localStorage.removeItem('sq.' + k); } catch (e) {} }
  };
  var PRESETS = {
    low: { ms: 40, spn: 1024, help: '缓冲目标 40 ms，适合网络稳定时使用；卡顿后会自动增加缓冲。' },
    mid: { ms: 70, spn: 1024, help: '缓冲目标 70 ms，推荐。网络抖动时会自动加大缓冲，稳定后再收回。' },
    high: { ms: 150, spn: 2048, help: '缓冲目标 150 ms，适合 Wi-Fi 较弱或网络抖动明显的环境。' }
  };
  var S = {
    key: (params.get('k') || store.get('key', '')).trim().toLowerCase(),
    preset: PRESETS[store.get('preset', 'mid')] ? store.get('preset', 'mid') : 'mid',
    gain: (function () { var v = Number(store.get('gain', '100')); return Math.max(0, Math.min(2, (isFinite(v) ? v : 100) / 100)); })(),
    playing: false, reachable: false, keyBad: false, error: '',
    ctx: null, node: null, core: null, ws: null, wsOpen: false,
    rate: 48000, channels: 2, pcName: '', pcId: '',
    expectSeq: -1, lost: 0, bytes: 0, bps: 0, rtt: null, lastDataAt: 0, silentSince: 0,
    retry: 0, retryTimer: 0, rms: 0, bgUrl: '', view: 'home'
  };
  if (params.get('k')) store.set('key', S.key);
  if (S.key && !params.get('k') && history.replaceState) {
    params.set('k', S.key);
    history.replaceState(null, '', location.pathname + '?' + params.toString());
  }

  function setAudioSession() {
    try { if (navigator.audioSession) navigator.audioSession.type = 'playback'; } catch (e) {}
  }
  function ensureCtx() {
    if (!S.ctx) {
      var AC = window.AudioContext || window.webkitAudioContext;
      try { S.ctx = new AC({ latencyHint: 'interactive' }); } catch (e) { S.ctx = new AC(); }
      S.ctx.onstatechange = onCtxState;
    }
    if (S.ctx.state !== 'running') S.ctx.resume().catch(function () {});
  }
  function buildNode() {
    var size = PRESETS[S.preset].spn;
    if (S.node && S.node.bufferSize === size) return;
    if (S.node) { S.node.onaudioprocess = null; try { S.node.disconnect(); } catch (e) {} }
    var node = S.ctx.createScriptProcessor(size, 1, 2);
    node.onaudioprocess = function (e) {
      if (!S.core) return;
      var out = e.outputBuffer, left = out.getChannelData(0), right = out.getChannelData(1);
      S.core.process(left, right);
      var sum = 0;
      for (var i = 0; i < left.length; i += 2) sum += left[i] * left[i] + right[i] * right[i];
      S.rms = Math.sqrt(sum / left.length);
    };
    node.connect(S.ctx.destination);
    S.node = node;
  }
  function onCtxState() {
    if (!S.ctx) return;
    if (S.ctx.state === 'running') setOverlay(false);
    else if (S.playing && document.visibilityState === 'visible') setOverlay(true);
  }
  function setOverlay(on) { $('resumeOverlay').hidden = !on; }
  $('resumeBtn').addEventListener('click', function () {
    setAudioSession();
    if (S.ctx) S.ctx.resume().catch(function () {});
    if (S.core) S.core.reset();
    setOverlay(false);
  });

  function connectWS() {
    if (!S.playing || S.keyBad || S.ws) return;
    clearTimeout(S.retryTimer);
    var url = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ws?k=' + encodeURIComponent(S.key);
    var ws;
    try { ws = new WebSocket(url); } catch (e) { scheduleRetry(); return; }
    ws.binaryType = 'arraybuffer';
    S.ws = ws;
    S.wsOpen = false;
    S.expectSeq = -1;
    ws.onopen = function () {
      if (S.ws !== ws) return;
      S.wsOpen = true;
      S.retry = 0;
      S.lastDataAt = now();
      S.reachable = true;
      S.error = '';
      render();
    };
    ws.onmessage = function (ev) {
      if (S.ws !== ws) return;
      if (typeof ev.data === 'string') onText(ev.data); else onAudio(ev.data);
    };
    ws.onclose = function (ev) {
      if (S.ws !== ws) return;
      S.ws = null;
      S.wsOpen = false;
      if (ev.code === 4001) { badKey(); return; }
      if (S.playing) scheduleRetry();
      render();
    };
    ws.onerror = function () {};
    render();
  }
  function scheduleRetry() {
    clearTimeout(S.retryTimer);
    var delay = Math.min(3000, 400 * Math.pow(1.6, S.retry++));
    if (S.retry > 3) S.reachable = false;
    S.retryTimer = setTimeout(function () {
      if (S.playing && !S.ws) connectWS();
    }, delay);
  }
  function closeWS() {
    clearTimeout(S.retryTimer);
    if (S.ws) {
      var ws = S.ws;
      S.ws = null;
      S.wsOpen = false;
      try { ws.close(); } catch (e) {}
    }
  }
  function onText(text) {
    var message;
    try { message = JSON.parse(text); } catch (e) { return; }
    if (message.type === 'hello') {
      S.rate = message.rate || 48000;
      S.channels = message.channels || 2;
      S.pcName = message.name || S.pcName;
      S.pcId = message.id || S.pcId;
      if (S.core && S.ctx) S.core.setRates(S.rate, S.ctx.sampleRate);
      setupMediaSession();
      render();
    } else if (message.type === 'pong' && typeof message.t === 'number') {
      S.rtt = now() - message.t;
    }
  }
  function onAudio(buffer) {
    if (buffer.byteLength < 8 || !S.core) return;
    var view = new DataView(buffer);
    if (view.getUint8(0) !== 1) return;
    var flags = view.getUint8(1), sequence = view.getUint32(4, true);
    if (S.expectSeq >= 0 && sequence !== S.expectSeq) S.lost += (sequence - S.expectSeq) >>> 0;
    S.expectSeq = (sequence + 1) >>> 0;
    S.core.push(new Int16Array(buffer, 8), S.channels);
    var t = now();
    S.lastDataAt = t;
    S.bytes += buffer.byteLength;
    if (flags & 1) { if (!S.silentSince) S.silentSince = t; } else S.silentSince = 0;
  }

  function setupMediaSession() {
    if (!('mediaSession' in navigator)) return;
    try {
      navigator.mediaSession.metadata = new MediaMetadata({
        title: '电脑的声音', artist: S.pcName || '声桥', album: '声桥',
        artwork: [{ src: '/icon-512.png', sizes: '512x512', type: 'image/png' }]
      });
      navigator.mediaSession.setActionHandler('play', function () { if (!S.playing) start(); });
      navigator.mediaSession.setActionHandler('pause', function () { if (S.playing) stop(); });
      navigator.mediaSession.playbackState = S.playing ? 'playing' : 'paused';
    } catch (e) {}
  }
  function start() {
    if (S.keyBad) return;
    S.error = '';
    S.playing = true;
    S.rms = 0;
    S.bytes = 0;
    S.lost = 0;
    S.silentSince = 0;
    lastBytes = 0;
    lastTick = now();
    try {
      setAudioSession();
      ensureCtx();
      if (!S.core) S.core = new PlayerCore(S.rate, S.ctx.sampleRate);
      S.core.setRates(S.rate, S.ctx.sampleRate);
      S.core.setTarget(PRESETS[S.preset].ms);
      S.core.gain = S.gain;
      S.core.reset();
      buildNode();
      connectWS();
    } catch (e) {
      S.playing = false;
      S.error = '当前浏览器无法启动音频播放，请更新 Safari 或 Chrome 后重试。';
    }
    setupMediaSession();
    render();
  }
  function stop() {
    S.playing = false;
    closeWS();
    if (S.node) { S.node.onaudioprocess = null; try { S.node.disconnect(); } catch (e) {} S.node = null; }
    if (S.ctx && S.ctx.state === 'running') S.ctx.suspend().catch(function () {});
    if (S.core) S.core.reset();
    S.rms = 0;
    S.retry = 0;
    setOverlay(false);
    setupMediaSession();
    render();
  }
  $('play').addEventListener('click', function () { if (S.playing) stop(); else start(); });
  $('stopButton').addEventListener('click', function () { if (S.playing) stop(); });
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState !== 'visible' || !S.playing) return;
    if (S.core) S.core.reset();
    if (!S.ws) connectWS();
    if (S.ctx && S.ctx.state !== 'running') {
      S.ctx.resume().then(function () { setOverlay(S.ctx.state !== 'running'); }, function () { setOverlay(true); });
    } else setOverlay(false);
  });

  function selectSegment(id, value) {
    var buttons = $(id).querySelectorAll('button');
    for (var i = 0; i < buttons.length; i++) {
      buttons[i].setAttribute('aria-checked', buttons[i].getAttribute('data-v') === value ? 'true' : 'false');
    }
  }
  $('presetSeg').addEventListener('click', function (e) {
    var value = e.target.closest('button') && e.target.closest('button').getAttribute('data-v');
    if (!value || value === S.preset) return;
    S.preset = value;
    store.set('preset', value);
    if (S.core) S.core.setTarget(PRESETS[value].ms);
    if (S.playing && S.ctx) buildNode();
    render();
  });
  function setVol(percent) {
    percent = Math.max(0, Math.min(200, Math.round(percent)));
    S.gain = percent / 100;
    $('vol').value = String(percent);
    $('volNum').value = String(percent);
    $('vol').style.setProperty('--fill', (percent / 2) + '%');
    if (S.core) S.core.gain = S.gain;
  }
  setVol(Math.round(S.gain * 100));
  $('vol').addEventListener('input', function () {
    var value = +$('vol').value;
    setVol(value);
    store.set('gain', String(value));
  });
  $('volNum').addEventListener('change', function () {
    var value = Math.max(0, Math.min(200, +$('volNum').value || 0));
    setVol(value);
    store.set('gain', String(value));
  });

  function setView(view) {
    S.view = view === 'settings' ? 'settings' : 'home';
    $('homeView').hidden = S.view !== 'home';
    $('settingsView').hidden = S.view !== 'settings';
    $('pageTitle').textContent = S.view === 'home' ? '声桥' : '设置';
    var buttons = document.querySelectorAll('.bottom-nav button');
    for (var i = 0; i < buttons.length; i++) {
      var active = buttons[i].getAttribute('data-view') === S.view;
      buttons[i].classList.toggle('active', active);
      if (active) buttons[i].setAttribute('aria-current', 'page'); else buttons[i].removeAttribute('aria-current');
    }
  }
  document.querySelector('.bottom-nav').addEventListener('click', function (e) {
    var button = e.target.closest('button[data-view]');
    if (button) setView(button.getAttribute('data-view'));
  });
  $('detailsButton').addEventListener('click', function () {
    renderStats();
    var dialog = $('detailsDialog');
    if (dialog.showModal) dialog.showModal(); else dialog.setAttribute('open', '');
  });
  $('detailsClose').addEventListener('click', function () {
    var dialog = $('detailsDialog');
    if (dialog.close) dialog.close(); else dialog.removeAttribute('open');
  });
  $('detailsDialog').addEventListener('click', function (e) {
    var dialog = $('detailsDialog');
    if (e.target === dialog) {
      if (dialog.close) dialog.close(); else dialog.removeAttribute('open');
    }
  });

  function currentTheme() {
    var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    return document.documentElement.getAttribute('data-theme') || (dark ? 'dark' : 'light');
  }
  function updateThemeColor() {
    var custom = store.get('bgColor', '');
    $('themeColor').setAttribute('content', custom || (currentTheme() === 'dark' ? '#101614' : '#F2F6F4'));
  }
  function setTheme(theme, persist) {
    if (theme !== 'light' && theme !== 'dark') theme = 'system';
    if (theme === 'system') document.documentElement.removeAttribute('data-theme');
    else document.documentElement.setAttribute('data-theme', theme);
    if (persist) store.set('theme', theme);
    selectSegment('themeSeg', theme);
    if (!store.get('bgColor', '')) $('backgroundColor').value = currentTheme() === 'dark' ? '#101614' : '#f2f6f4';
    updateThemeColor();
  }
  $('themeSeg').addEventListener('click', function (e) {
    var button = e.target.closest('button[data-v]');
    if (button) setTheme(button.getAttribute('data-v'), true);
  });
  if (window.matchMedia) {
    var colorQuery = window.matchMedia('(prefers-color-scheme: dark)');
    if (colorQuery.addEventListener) colorQuery.addEventListener('change', function () { setTheme(store.get('theme', 'system'), false); });
  }
  function updateBackgroundStatus() {
    var color = store.get('bgColor', '');
    $('backgroundStatus').textContent = S.bgUrl ? '当前使用自定义图片，图片只保存在这个浏览器。' :
      color ? '当前使用自定义纯色 ' + color.toUpperCase() + '。' : '当前使用与系统主题搭配的默认背景。';
  }
  function applyBackground() {
    var color = store.get('bgColor', '');
    $('backdrop').style.backgroundColor = color || '';
    $('backdrop').style.backgroundImage = S.bgUrl ? 'url("' + S.bgUrl + '")' : '';
    $('backgroundColor').value = color || (currentTheme() === 'dark' ? '#101614' : '#f2f6f4');
    updateBackgroundStatus();
    updateThemeColor();
  }
  function openBackgroundDB() {
    return new Promise(function (resolve, reject) {
      var request = indexedDB.open('shengqiao-web', 1);
      request.onupgradeneeded = function () { request.result.createObjectStore('assets'); };
      request.onsuccess = function () { resolve(request.result); };
      request.onerror = function () { reject(request.error); };
    });
  }
  function loadBackgroundImage() {
    if (!window.indexedDB) return;
    openBackgroundDB().then(function (db) {
      var request = db.transaction('assets', 'readonly').objectStore('assets').get('background');
      request.onsuccess = function () {
        if (request.result) {
          S.bgUrl = URL.createObjectURL(request.result);
          applyBackground();
        }
        db.close();
      };
      request.onerror = function () { db.close(); };
    }).catch(function () {});
  }
  $('backgroundColor').addEventListener('input', function () {
    store.set('bgColor', $('backgroundColor').value);
    applyBackground();
  });
  $('backgroundImage').addEventListener('change', function () {
    var file = $('backgroundImage').files && $('backgroundImage').files[0];
    if (!file) return;
    if (!file.type || file.type.indexOf('image/') !== 0 || file.size > 30 * 1024 * 1024) {
      $('backgroundStatus').textContent = '请选择不超过 30 MB 的图片文件。';
      $('backgroundImage').value = '';
      return;
    }
    openBackgroundDB().then(function (db) {
      var request = db.transaction('assets', 'readwrite').objectStore('assets').put(file, 'background');
      request.onsuccess = function () {
        if (S.bgUrl) URL.revokeObjectURL(S.bgUrl);
        S.bgUrl = URL.createObjectURL(file);
        db.close();
        applyBackground();
      };
      request.onerror = function () { db.close(); $('backgroundStatus').textContent = '图片保存失败，请换一张重试。'; };
    }).catch(function () { $('backgroundStatus').textContent = '当前浏览器无法保存图片，请换用 Safari 或 Chrome。'; });
    $('backgroundImage').value = '';
  });
  $('backgroundReset').addEventListener('click', function () {
    store.remove('bgColor');
    if (S.bgUrl) URL.revokeObjectURL(S.bgUrl);
    S.bgUrl = '';
    if (window.indexedDB) {
      openBackgroundDB().then(function (db) {
        var request = db.transaction('assets', 'readwrite').objectStore('assets').delete('background');
        request.onsuccess = request.onerror = function () { db.close(); };
      }).catch(function () {});
    }
    applyBackground();
  });

  function badKey() {
    S.keyBad = true;
    if (S.playing) stop();
    $('keyCard').hidden = false;
    $('keyMsg').textContent = S.key ? '访问码不正确，请重新扫描电脑上的二维码，或输入当前访问码。' : '请输入电脑声桥页面显示的访问码。';
    render();
  }
  $('keyForm').addEventListener('submit', function (e) {
    e.preventDefault();
    var key = $('keyInput').value.trim().toLowerCase();
    if (!/^[a-z0-9]{4,16}$/.test(key)) {
      $('keyInput').setCustomValidity('请输入 4–16 位英文字母或数字');
      $('keyInput').reportValidity();
      return;
    }
    $('keyInput').setCustomValidity('');
    S.key = key;
    store.set('key', key);
    params.set('k', key);
    if (history.replaceState) history.replaceState(null, '', location.pathname + '?' + params.toString());
    checkServer();
  });
  $('keyInput').addEventListener('input', function () { $('keyInput').setCustomValidity(''); });
  function checkServer() {
    fetch('/api/check?k=' + encodeURIComponent(S.key), { cache: 'no-store' }).then(function (response) {
      if (response.status === 403) { badKey(); return null; }
      return response.ok ? response.json() : null;
    }).then(function (info) {
      if (!info) return;
      S.keyBad = false;
      S.reachable = true;
      S.pcName = info.name || '';
      S.pcId = info.id || '';
      $('keyCard').hidden = true;
      render();
    }).catch(function () { S.reachable = false; render(); });
  }

  function fmtMs(ms) { return ms == null || isNaN(ms) ? '—' : Math.round(ms) + ' ms'; }
  function estLatency() {
    if (!S.core || !S.ctx) return null;
    var stats = S.core.stats();
    var base = (S.ctx.baseLatency || 0) + (S.ctx.outputLatency || 0);
    var buffer = S.node ? S.node.bufferSize / S.ctx.sampleRate : 0;
    return stats.emaMs + buffer * 1000 + base * 1000 + (S.rtt != null ? S.rtt / 2 : 5) + 10;
  }
  function render() {
    selectSegment('presetSeg', S.preset);
    $('presetHelp').textContent = PRESETS[S.preset].help;
    $('pcName').textContent = S.reachable ? (S.pcName || '电脑') : '尚未连接';
    $('addressText').textContent = S.reachable ? location.host : '打开电脑上的声桥，用手机扫码即可连接。';
    $('play').setAttribute('aria-checked', S.playing ? 'true' : 'false');
    $('play').disabled = !S.playing && (S.keyBad || !S.reachable);
    $('stopButton').disabled = !S.playing;
    $('detailsButton').disabled = !S.reachable;
    $('localNotice').hidden = !/^(127\.0\.0\.1|localhost|\[::1\])$/.test(location.hostname);

    var pill = $('connectionPill');
    pill.classList.toggle('ok', S.reachable && (!S.playing || S.wsOpen));
    pill.classList.toggle('warn', S.keyBad || (S.playing && !S.reachable));
    if (S.keyBad) $('connText').textContent = '需要访问码';
    else if (S.playing && !S.wsOpen) $('connText').textContent = S.retry > 1 ? '正在重连' : '正在连接';
    else if (S.reachable) $('connText').textContent = '已连接';
    else $('connText').textContent = '连不上电脑';

    var message = '', alert = false;
    if (S.error) { message = S.error; alert = true; }
    else if (S.keyBad) { message = '请先输入正确的访问码。'; alert = true; }
    else if (!S.playing) {
      message = S.reachable ? '打开扬声器，电脑声音会实时传到手机。' : '确认电脑声桥正在运行，且手机与电脑在同一网络。';
      alert = !S.reachable;
    } else if (!S.wsOpen) {
      message = S.retry > 3 ? '连接中断，正在自动重连…' : '正在连接电脑…';
      alert = S.retry > 3;
    } else if (S.core && S.core.state === 'buffering') message = '正在缓冲…';
    else if (S.silentSince && now() - S.silentSince > 1500) message = '已连接，电脑当前没有声音输出。';
    else {
      var latency = estLatency();
      message = '正在实时播放' + (latency ? ' · 约 ' + Math.round(latency / 10) * 10 + ' ms' : '') + '，可以锁屏。';
    }
    $('playStatus').textContent = message;
    $('playStatus').classList.toggle('danger-text', alert);
    renderStats();
    renderLevel();
  }
  function renderLevel() {
    var playing = S.playing && S.core && S.core.state === 'playing';
    var db = playing && S.rms > 1e-6 ? 20 * Math.log10(S.rms) : -60;
    var value = Math.max(0, Math.min(100, (db + 60) / 60 * 100));
    $('levelBar').style.width = value.toFixed(1) + '%';
    $('levelMeter').setAttribute('aria-valuenow', String(Math.round(value)));
    $('levelText').textContent = playing ? (db <= -59.9 ? '低于 -60 dB' : db.toFixed(1) + ' dB') : '暂无电平数据';
  }
  function renderStats() {
    var live = S.playing && S.core;
    $('stPc').textContent = S.pcName || '—';
    $('stAddress').textContent = location.host;
    $('stLat').textContent = live ? fmtMs(estLatency()) : '—';
    if (live) {
      var stats = S.core.stats();
      $('stBuf').textContent = Math.round(stats.emaMs) + ' / ' + Math.round(stats.targetMs) + ' ms';
      $('stUnd').textContent = String(stats.underruns);
      $('stSkips').textContent = String(stats.skips);
      $('stOverflows').textContent = String(stats.overflows);
    } else {
      $('stBuf').textContent = $('stUnd').textContent = $('stSkips').textContent = $('stOverflows').textContent = '—';
    }
    $('stNet').textContent = live ? (S.bps / 1e6).toFixed(2) + ' Mbps' : '—';
    $('stRtt').textContent = live && S.rtt != null ? fmtMs(S.rtt) : '—';
    $('stLost').textContent = live ? S.lost + ' 帧' : '—';
    $('stRate').textContent = S.ctx ? S.ctx.sampleRate + ' Hz' : '—';
  }

  var lastBytes = 0, lastTick = now();
  setInterval(function () {
    var t = now();
    S.bps = (S.bytes - lastBytes) * 8 / Math.max(.1, (t - lastTick) / 1000);
    lastBytes = S.bytes;
    lastTick = t;
    if (S.playing && S.wsOpen && S.ws) {
      try {
        S.ws.send(JSON.stringify({ type: 'ping', t: now() }));
        var stats = S.core.stats();
        S.ws.send(JSON.stringify({ type: 'stats', lat: Math.round(estLatency() || 0), buf: Math.round(stats.emaMs),
          tgt: Math.round(stats.targetMs), und: stats.underruns, rtt: S.rtt == null ? null : Math.round(S.rtt) }));
      } catch (e) {}
      if (t - S.lastDataAt > 4000) {
        try { S.ws.close(); } catch (e) {}
      }
    }
    renderStats();
  }, 1000);
  setInterval(function () { renderLevel(); if (S.playing) render(); }, 300);
  if (store.get('homeTip', '') === 'done') $('homeTip').hidden = true;
  $('homeTipClose').addEventListener('click', function () { $('homeTip').hidden = true; store.set('homeTip', 'done'); });
  window.__sq = S;
  setTheme(store.get('theme', 'system'), false);
  applyBackground();
  loadBackgroundImage();
  render();
  checkServer();
  setInterval(function () { if (!S.playing) checkServer(); }, 5000);
})();
