(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const navButtons = [...document.querySelectorAll('nav [data-page]')];
  const denoiseButtons = [...$('denoise').querySelectorAll('button')];
  const systemTheme = matchMedia('(prefers-color-scheme: dark)');
  const defaultPairHint = $('pairHint').textContent;
  const viaNames = { usb: 'USB', usbnet: 'USB 共享', wifi: 'Wi-Fi' };
  const notes = {
    off: '保留原始声音。',
    light: '减少风扇、空调等持续噪声，增加约 10 毫秒延迟。',
    rnnoise: '压低键盘和环境杂音，增加约 20 毫秒延迟。开麦后约 0.2 秒为预热静音。',
    strong: 'RNNoise 加噪声门，停顿约 0.2 秒后静音，适合较吵的环境。增加约 20 毫秒延迟。'
  };

  let online = false;
  let playingChime = false;
  let current = '';
  let usbUrls = [];
  let addresses = [];
  let denoiseMode = '';
  let denoiseModes = [];
  let savingDenoise = false;
  let copyTimer;
  let listenerMarkup;
  let pairingBusy = false;
  let pairingEpoch = 0;
  let accessKey = '';
  const deviceFields = [
    { id: 'device', path: '/api/device', listPath: '/api/devices', fallback: 'default', loaded: false, saving: false },
    { id: 'micDevice', path: '/api/mic-device', listPath: '/api/mic-devices', fallback: 'auto', loaded: false, saving: false }
  ];

  function showPage(page, focusId) {
    for (const button of navButtons) {
      const selected = button.dataset.page === page;
      $(button.dataset.page).hidden = !selected;
      if (selected) {
        button.setAttribute('aria-current', 'page');
        $('pageTitle').textContent = button.querySelector('span').textContent;
      } else {
        button.removeAttribute('aria-current');
      }
    }
    $('content').scrollTop = 0;
    if (focusId) $(focusId).focus();
  }

  for (const button of navButtons) button.addEventListener('click', () => showPage(button.dataset.page));
  for (const button of document.querySelectorAll('[data-open]')) {
    button.addEventListener('click', () => {
      const page = button.dataset.open;
      showPage(page, page === 'home' ? 'pairTitle' : null);
      if (page === 'devices') navButtons.find(item => item.dataset.page === page).focus();
    });
  }

  function applyTheme() {
    const choice = $('theme').value;
    const dark = choice === 'dark' || (choice === 'system' && systemTheme.matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    window.pywebview?.api?.set_title_theme?.(dark).catch(() => {});
  }

  try {
    const saved = localStorage.getItem('sq-desktop-theme');
    if (saved === 'light' || saved === 'dark') $('theme').value = saved;
  } catch (_) {
    // The theme still works for this session if storage is unavailable.
  }
  $('theme').addEventListener('change', () => {
    try { localStorage.setItem('sq-desktop-theme', $('theme').value); } catch (_) {}
    applyTheme();
  });
  systemTheme.addEventListener('change', applyTheme);
  window.addEventListener('pywebviewready', applyTheme);
  applyTheme();

  function text(id, value) {
    if ($(id).textContent !== value) $(id).textContent = value;
  }

  function state(id, value, kind = '') {
    $(id).dataset.state = kind;
    text(id === 'service' ? 'serviceText' : id, value);
  }

  function feedback(id, message = '') {
    text(id, message);
    $(id).hidden = !message;
  }

  async function request(path, body) {
    const response = await fetch(path, body === undefined ? { cache: 'no-store' } : {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!response.ok) {
      const detail = await response.json().catch(() => ({}));
      throw new Error(detail.error || '请求失败（HTTP ' + response.status + '）');
    }
    return response.json();
  }

  function setLevel(id, level) {
    const db = level > 0 ? 20 * Math.log10(level) : -60;
    $(id).style.transform = 'scaleX(' + Math.max(0, Math.min(1, (db + 60) / 60)) + ')';
  }

  function setQr(url) {
    const changed = current !== url;
    current = url;
    const qr = $('qr');
    if (changed) {
      qr.hidden = true;
      $('qrPlaceholder').removeAttribute('hidden');
      if (url) qr.src = '/api/qr.svg?u=' + encodeURIComponent(url);
      else qr.removeAttribute('src');
    }
    text('url', url ? new URL(url).host : '暂无可用地址');
    const usb = usbUrls.includes(url);
    state('pairType', usb ? 'USB 共享' : 'Wi-Fi', usb ? 'active' : '');
    text('pairHint', usb ? '可用相机扫码收听，也可在 App 中手动输入地址和访问码。' : defaultPairHint);
    for (const button of $('alts').children) {
      if (button.dataset.url === url) button.setAttribute('aria-current', 'true');
      else button.removeAttribute('aria-current');
    }
    $('copy').disabled = !online || !url;
  }

  $('qr').addEventListener('load', () => {
    $('qr').hidden = false;
    $('qrPlaceholder').setAttribute('hidden', '');
    feedback('copyFeedback');
  });
  $('qr').addEventListener('error', () => {
    feedback('copyFeedback', '二维码加载失败，可用连接地址和访问码手动连接。');
  });

  function renderAddresses(s) {
    usbUrls = s.usb?.netUrls || [];
    const wifi = (s.urls || []).slice(0, 6);
    const next = [...new Set([...wifi, ...usbUrls, ...(s.localUrl ? [s.localUrl] : [])])];
    if (next.join('\n') !== addresses.join('\n')) {
      addresses = next;
      const items = addresses.map(url => {
        const button = document.createElement('button');
        button.type = 'button';
        button.dataset.url = url;
        button.textContent = (usbUrls.includes(url) ? 'USB 共享 · ' : '') + new URL(url).host;
        if (url === current) button.setAttribute('aria-current', 'true');
        button.addEventListener('click', () => {
          setQr(url);
          $('altsBox').open = false;
          $('altsBox').querySelector('summary').focus();
        });
        return button;
      });
      $('alts').replaceChildren(...items);
    }
    $('altsBox').hidden = addresses.length < 2;
    const sameHost = current && addresses.find(url => new URL(url).host === new URL(current).host);
    setQr(addresses.includes(current) ? current : (sameHost || usbUrls[0] || wifi[0] || s.localUrl || ''));
    $('copy').disabled = !current;
    if (!current) text('url', '暂无可用地址');
    $('keyline').hidden = !s.key;
    text('key', s.key || '');
    accessKey = s.key || '';
    pairingControls();
  }

  function pairingControls() {
    for (const id of ['refreshConnection', 'refreshUsb']) $(id).disabled = !online || pairingBusy;
    for (const id of ['editKey', 'saveKey', 'generateKey', 'customKey']) $(id).disabled = !online || pairingBusy || !accessKey;
  }

  function pairingFeedback(id, message, success = false) {
    $(id).dataset.state = success ? 'success' : 'error';
    feedback(id, message);
  }

  async function updatePairing(path, body, feedbackId) {
    if (!online || pairingBusy) return;
    pairingBusy = true;
    pairingEpoch++;
    pairingControls();
    pairingFeedback(feedbackId, '正在更新…', true);
    try {
      const s = await request(path, body);
      renderAddresses(s);
      renderUsb(s.usb);
      if (path === '/api/access-key') {
        $('customKey').value = s.key;
        pairingFeedback(feedbackId, s.changed ? '访问码已保存，二维码已更新。请在手机上用新码重新连接。' : '访问码未变更。', true);
      } else {
        const url = current;
        current = '';
        setQr(url);
        pairingFeedback(feedbackId, s.usb?.netUrls?.length ? '已刷新，检测到 USB 共享地址。' : '已刷新连接信息。未检测到 USB 共享地址时，请检查手机的网络共享开关。', true);
      }
    } catch (error) {
      pairingFeedback(feedbackId, error.message || '更新失败，请重试。');
    } finally {
      pairingBusy = false;
      pairingEpoch++;
      pairingControls();
    }
  }

  $('refreshConnection').addEventListener('click', () => updatePairing('/api/refresh', {}, 'pairFeedback'));
  $('refreshUsb').addEventListener('click', () => updatePairing('/api/refresh', {}, 'usbFeedback'));
  $('editKey').addEventListener('click', () => {
    $('keyForm').hidden = !$('keyForm').hidden;
    $('editKey').setAttribute('aria-expanded', String(!$('keyForm').hidden));
    if (!$('keyForm').hidden) {
      $('customKey').value = accessKey;
      $('customKey').focus();
    }
  });
  $('keyForm').addEventListener('submit', event => {
    event.preventDefault();
    const key = $('customKey').value.trim();
    if (!/^[a-z0-9]{4,16}$/i.test(key)) {
      pairingFeedback('pairFeedback', '访问码须为 4–16 位英文字母或数字。');
      return;
    }
    updatePairing('/api/access-key', { action: 'set', key }, 'pairFeedback');
  });
  $('generateKey').addEventListener('click', () => updatePairing('/api/access-key', { action: 'generate' }, 'pairFeedback'));

  document.addEventListener('click', event => {
    if ($('altsBox').open && !$('altsBox').contains(event.target)) $('altsBox').open = false;
  });
  $('altsBox').addEventListener('keydown', event => {
    if (event.key !== 'Escape' || !$('altsBox').open) return;
    $('altsBox').open = false;
    $('altsBox').querySelector('summary').focus();
  });

  $('copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(current);
      feedback('copyFeedback');
      clearTimeout(copyTimer);
      text('copyLabel', '已复制');
      copyTimer = setTimeout(() => text('copyLabel', '复制链接'), 1600);
    } catch (_) {
      feedback('copyFeedback', '无法复制，请使用二维码或手动输入连接地址。');
    }
  });

  $('chime').addEventListener('click', async () => {
    playingChime = true;
    $('chime').disabled = true;
    text('chimeLabel', '正在发送');
    feedback('chimeFeedback');
    try {
      await request('/api/chime', {});
      await new Promise(resolve => setTimeout(resolve, 1400));
    } catch (_) {
      feedback('chimeFeedback', '测试音发送失败，请重试。');
    } finally {
      playingChime = false;
      $('chime').disabled = !online;
      text('chimeLabel', '播放测试音');
    }
  });

  function loadDevices(field) {
    if (field.loaded || field.saving) return;
    field.saving = true;
    request(field.listPath).then(data => {
      const select = $(field.id);
      const isCapture = field.id === 'device';
      const devices = data.devices || [];
      const cable = devices.find(device => device.id === data.cable);
      const fallbackLabel = isCapture ? '跟随系统默认输出设备'
        : (cable ? '自动 · ' + cable.name : '自动 · 未检测到 VB-CABLE');
      select.replaceChildren(new Option(fallbackLabel, field.fallback), ...devices.map(device => {
        const name = device.name + (isCapture && device.default ? '（当前默认）' : '');
        return new Option(name, device.id);
      }));
      select.value = data.selected || field.fallback;
      if (!select.value) select.value = field.fallback;
      field.loaded = !data.error;
      if (data.error) select.replaceChildren(new Option('无法读取设备，正在重试…'));
    }).catch(() => {}).finally(() => {
      field.saving = false;
      $(field.id).disabled = !online || !field.loaded;
    });
  }

  for (const field of deviceFields) {
    $(field.id).addEventListener('change', async () => {
      const select = $(field.id);
      field.saving = true;
      select.disabled = true;
      feedback('settingsFeedback');
      try {
        await request(field.path, { id: select.value });
      } catch (_) {
        field.loaded = false;
        feedback('settingsFeedback', '音频设备设置失败，请重试。');
      } finally {
        field.saving = false;
        select.disabled = !online || !field.loaded;
      }
    });
  }

  function renderDenoise(mic) {
    denoiseModes = mic.denoiseModes || [];
    if (!savingDenoise) denoiseMode = mic.denoise || '';
    for (const button of denoiseButtons) {
      button.setAttribute('aria-pressed', String(button.dataset.v === denoiseMode));
      button.disabled = !online || savingDenoise || !denoiseModes.includes(button.dataset.v);
    }
    text('denoiseNote', mic.denoiseError || notes[denoiseMode] || '');
    $('denoiseNote').classList.toggle('error-text', Boolean(mic.denoiseError));
  }

  $('denoise').addEventListener('click', async event => {
    const button = event.target.closest('button');
    if (!button || button.disabled || button.dataset.v === denoiseMode) return;
    savingDenoise = true;
    for (const mode of denoiseButtons) mode.disabled = true;
    feedback('settingsFeedback');
    try {
      const result = await request('/api/denoise', { mode: button.dataset.v });
      denoiseMode = result.mode;
      renderDenoise({ denoise: denoiseMode, denoiseModes });
      if (denoiseMode !== button.dataset.v) feedback('settingsFeedback', '当前设备不支持此降噪方式。');
    } catch (_) {
      feedback('settingsFeedback', '降噪设置失败，请重试。');
    } finally {
      savingDenoise = false;
      for (const mode of denoiseButtons) mode.disabled = !online || !denoiseModes.includes(mode.dataset.v);
    }
  });

  $('usbNetQr').addEventListener('click', () => {
    if (!online || !usbUrls.length) return;
    setQr(usbUrls[0]);
    showPage('home', 'pairTitle');
  });

  function renderUsb(usb = {}) {
    const dev = (usb.devices || [])[0];
    if (dev?.state === 'connected') {
      state('usbTag', '已直连', 'active');
      text('usbMsg', 'iPhone 已通过 USB 直连。');
      text('usbHint', '');
    } else if (dev) {
      state('usbTag', '已检测到 iPhone');
      text('usbMsg', '请解锁 iPhone，在手机上打开声桥并保持在前台。');
      text('usbHint', '首次连接时，请在手机上点「信任此电脑」。');
    } else if (usb.net) {
      state('usbTag', '共享网络', 'active');
      const platforms = [...new Set((usb.netAdapters || []).map(ad => ad.platform === 'android' ? '安卓' : 'iPhone'))].join(' / ') || '手机';
      text('usbMsg', usbUrls.length ? platforms + ' USB 共享网络已就绪。' : '已检测到共享网卡，正在获取地址…');
      text('usbHint', usbUrls.length ? '在手机声桥中连接电脑，或手动填写下方 USB 共享地址和访问码。' : '请确认手机已开启 USB 网络共享，稍后点击「刷新检测」。');
    } else {
      state('usbTag', '等待检测');
      text('usbMsg', '插入数据线后，按手机类型完成连接。');
      text('usbHint', '开启 USB 网络共享后，可点击下方按钮重新检测。');
    }
    $('usbNetQr').hidden = !online || !usbUrls.length;
  }

  function renderAudio(s) {
    let label = '等待采集', kind = '';
    if (s.state === 'running') { label = s.level > .001 ? '信号正常' : '暂无信号'; kind = s.level > .001 ? 'active' : ''; }
    if (s.state === 'opening') label = '正在打开';
    if (s.state === 'error') { label = '采集异常'; kind = 'error'; }
    state('srcTag', label, kind);
    text('source', s.state === 'error' ? s.error || '无法打开采集设备，正在重试。' : s.device || '等待电脑输出声音。');
    $('source').classList.toggle('error-text', s.state === 'error');
    text('captureDetail', s.source || '');
    setLevel('bar', s.level);

    const mic = s.mic || {};
    label = '等待接入';
    kind = '';
    let info = '在手机声桥 App 中开启麦克风。';
    if (mic.state === 'no-cable') {
      label = '未找到输出';
      kind = 'warning';
      info = '未检测到 VB-CABLE，可在设置中手动选择输出设备。';
    } else if (mic.state === 'error') {
      label = '输出异常';
      kind = 'error';
      info = mic.error || '无法打开麦克风输出设备。';
    } else if (mic.active && mic.state === 'running') {
      label = '接收中';
      kind = 'active';
      info = mic.device + ' · 输出延迟约 ' + mic.latencyMs + ' ms';
    } else if (mic.active) {
      label = '正在打开';
      info = mic.device || '正在打开麦克风输出设备…';
    }
    state('micTag', label, kind);
    text('micState', info);
    $('micState').classList.toggle('error-text', kind === 'error');
    setLevel('micBar', mic.active ? mic.level : 0);
    $('conflict').hidden = !s.conflict;
    renderDenoise(mic);
  }

  function escapeText(value) {
    return String(value ?? '').replace(/[&<>]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[char]));
  }

  function fmtSince(time) {
    const seconds = Math.max(0, Date.now() / 1000 - time);
    if (seconds < 60) return Math.floor(seconds) + ' 秒';
    if (seconds < 3600) return Math.floor(seconds / 60) + ' 分钟';
    return (seconds / 3600).toFixed(1) + ' 小时';
  }

  function renderListeners(listeners) {
    const count = listeners.length;
    const listening = listeners.filter(client => client.listen).length;
    const mic = listeners.some(client => client.mic);
    text('connectionText', count ? count + ' 台设备已连接' + (listening ? ' · ' + listening + ' 台正在收听' : '') +
      (mic ? ' · 麦克风已接入' : '') : '等待手机连接');
    text('deviceCount', count + ' 台');
    text('navCount', String(count));
    $('navCount').hidden = !count;
    $('emptyDevices').hidden = Boolean(count);
    const markup = listeners.map(client => {
      const chips = [];
      if (client.listen) chips.push(client.mode === 'bg' ? '网页收听' : '收听');
      if (client.mode === 'live' && client.listen) chips.push(client.codec === 'opus' ? 'Opus · ' + client.bitrate + ' kbps' : '原始音质');
      if (client.mic) chips.push('麦克风');
      const latency = client.mode === 'bg' ? '延迟约 2–5 秒' : (client.latency ? '延迟 ' + client.latency + ' ms' : '');
      const underruns = client.mode === 'bg' || client.underruns == null ? '' : '卡顿 ' + client.underruns + ' 次';
      const duration = '已连接 ' + fmtSince(client.since);
      return '<div class="phone"><span class="tile"><svg class="icon" aria-hidden="true"><use href="#i-phone"/></svg></span>' +
        '<div><div class="name">' + escapeText(client.device || '手机') + '</div><div class="meta">' +
        '<span class="chip via">' + (viaNames[client.via] || 'Wi-Fi') + '</span>' +
        chips.map(chip => '<span class="chip">' + escapeText(chip) + '</span>').join('') + escapeText(client.ip) +
        '</div></div><div class="nums">' + [latency, underruns, duration].filter(Boolean).map(escapeText).join('<br>') + '</div></div>';
    }).join('');
    if (markup !== listenerMarkup) {
      $('listeners').innerHTML = markup;
      listenerMarkup = markup;
    }
  }

  function renderLog(lines) {
    const log = $('log');
    const value = lines.join('\n');
    if (log.textContent === value) return;
    const atBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 4;
    log.textContent = value;
    if (atBottom) log.scrollTop = log.scrollHeight;
  }

  async function poll() {
    const epoch = pairingEpoch;
    try {
      const s = await request('/api/status');
      if (epoch !== pairingEpoch || pairingBusy) return;
      online = true;
      state('service', s.state === 'error' ? '采集异常，正在重试' : '本机服务运行中', s.state === 'error' ? 'error' : 'ready');
      text('hostname', s.hostname || '此电脑');
      $('hostname').title = s.hostname || '';
      text('version', s.version ? '版本 ' + s.version : '本机');
      for (const field of deviceFields) {
        loadDevices(field);
        $(field.id).disabled = !field.loaded || field.saving;
      }
      $('chime').disabled = playingChime;
      renderAddresses(s);
      renderUsb(s.usb);
      renderAudio(s);
      renderListeners(s.listeners || []);
      renderLog(s.log || []);
    } catch (_) {
      if (epoch !== pairingEpoch || pairingBusy) return;
      online = false;
      pairingControls();
      state('service', '本机服务未连接', 'error');
      for (const field of deviceFields) {
        field.loaded = false;
        $(field.id).disabled = true;
      }
      for (const button of denoiseButtons) button.disabled = true;
      $('chime').disabled = true;
      $('copy').disabled = true;
      $('usbNetQr').hidden = true;
      state('srcTag', '暂无数据');
      state('micTag', '暂无数据');
      setLevel('bar', 0);
      setLevel('micBar', 0);
      renderListeners([]);
      text('connectionText', '连接状态暂不可用');
    } finally {
      setTimeout(poll, 400);
    }
  }
  poll();
})();
