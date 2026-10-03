/* 声桥 · 播放核心
 *
 * 环形缓冲 + 自适应抖动缓冲 + 漂移补偿重采样（Catmull-Rom 三次插值）。
 * - 网络抖动：缓冲目标随卡顿自动加大，稳定后慢慢收回，尽量低延迟。
 * - 时钟漂移：电脑声卡和手机的时钟总有微小差异，靠 ±0.5% 以内的变速把缓冲稳在目标附近（听不出来）。
 * - 数据突发涌入：超出目标太多时交叉淡化跳到最新，避免延迟越积越大。
 * - 断流：淡出到静音，攒够缓冲后淡入继续，没有爆音。
 * 浏览器（ScriptProcessor）和 Node（单元测试）共用这一份代码。
 */
(function (root) {
  'use strict';

  var CAP = 1 << 17;            // 131072 帧 ≈ 2.7 s @48k
  var MASK = CAP - 1;
  var FADE = 128;               // 淡入淡出长度（帧）
  var KP = 0.1;                 // 变速比例系数：误差 1 秒 → 10%（再被 MAXADJ 限住）
  var MAXADJ = 0.005;           // 最大变速 ±0.5%
  var EMA_TAU = 1.0;            // 缓冲水位平滑时间常数（秒）
  var RELAX_MARGIN = 0.035;     // 15 秒内最低水位高于它，才收回 10 ms 附加缓冲

  function catmull(y0, y1, y2, y3, f) {
    return y1 + 0.5 * f * (y2 - y0 + f * (2 * y0 - 5 * y1 + 4 * y2 - y3 + f * (3 * (y1 - y2) + y3 - y0)));
  }

  function softclip(x) {
    if (x > 0.95) return 0.95 + 0.05 * Math.tanh((x - 0.95) / 0.05);
    if (x < -0.95) return -0.95 - 0.05 * Math.tanh((-x - 0.95) / 0.05);
    return x;
  }

  function PlayerCore(srcRate, dstRate) {
    this.L = new Float32Array(CAP);
    this.R = new Float32Array(CAP);
    this.targetMs = 70;          // 基础缓冲目标
    this.extraMs = 0;            // 自适应附加
    this.maxExtraMs = 400;
    this.autoAdapt = true;
    this.gain = 1;
    this.underruns = 0;
    this.skips = 0;
    this.overflows = 0;
    this.framesIn = 0;
    this.peak = 0;
    this.clock = 0;              // 已输出音频的时长（秒），作为内部时间基准
    this.setRates(srcRate || 48000, dstRate || 48000);
    this.reset();
  }

  PlayerCore.prototype.setRates = function (src, dst) {
    this.srcRate = src;
    this.dstRate = dst;
    this.ratio0 = src / dst;
  };

  PlayerCore.prototype.reset = function () {
    this.L.fill(0, 0, 8);
    this.R.fill(0, 0, 8);
    this.w = 2;                  // 写指针（绝对帧号）
    this.r = 1;                  // 读指针（绝对帧号），r-1 必须有效
    this.frac = 0;               // 读指针小数部分
    this.state = 'buffering';
    this.env = 0;
    this.ema = 0;
    this.adj = 0;
    this.lastUnderrunAt = -1e9;
    this.lastRelaxAt = this.clock;
    this.playStartAt = this.clock;
    this.winMin = Infinity;
    this.forceTrim = false;
    this.lastL = 0;
    this.lastR = 0;
  };

  PlayerCore.prototype.targetSec = function () {
    return (this.targetMs + this.extraMs) / 1000;
  };

  /* 切换延迟档位：立即生效（多余的缓冲在下一次回调里交叉淡化跳过）。 */
  PlayerCore.prototype.setTarget = function (ms) {
    this.targetMs = ms;
    this.extraMs = 0;
    this.forceTrim = true;
  };

  PlayerCore.prototype.available = function () {
    return this.w - this.r;
  };

  PlayerCore.prototype.levelSec = function () {
    return Math.max(0, this.w - this.r - this.frac) / this.srcRate;
  };

  /* pcm: Int16Array（交错）。 */
  PlayerCore.prototype.push = function (pcm, channels) {
    channels = channels || 2;
    var n = (pcm.length / channels) | 0;
    if (n <= 0) return;
    if (this.w + n - this.r > CAP - 16) {
      // 很久没被消费（比如页面被挂起），只保留最新的一小段
      var keep = Math.min(CAP >> 1, Math.round(this.targetSec() * this.srcRate) + n);
      this.r = this.w + n - keep;
      this.frac = 0;
      this.overflows++;
    }
    var L = this.L, R = this.R, w = this.w, s = 1 / 32768, peak = 0, i, j, k, a, b;
    if (channels === 2) {
      for (i = 0, j = 0; i < n; i++, j += 2) {
        k = (w + i) & MASK;
        a = pcm[j]; b = pcm[j + 1];
        L[k] = a * s; R[k] = b * s;
        if (a < 0) a = -a;
        if (b < 0) b = -b;
        if (a > peak) peak = a;
        if (b > peak) peak = b;
      }
    } else {
      for (i = 0; i < n; i++) {
        k = (w + i) & MASK;
        a = pcm[i * channels];
        L[k] = R[k] = a * s;
        if (a < 0) a = -a;
        if (a > peak) peak = a;
      }
    }
    peak *= s;
    if (peak > this.peak) this.peak = peak;
    this.w = w + n;
    this.framesIn += n;
  };

  /* 渲染 [from, to) 区间的输出；返回实际渲染到的位置（< to 表示断流）。 */
  PlayerCore.prototype._render = function (outL, outR, from, to, step) {
    var avail = this.w - this.r;
    // 第 i 帧需要读到 r + floor(frac + i*step) + 2，必须 < w
    var maxIdx = avail - 3;            // floor(frac + i*step) 的上限
    var canOut;
    if (maxIdx < 0) canOut = 0;
    else canOut = Math.min(to - from, Math.floor((maxIdx - this.frac) / step) + 1);
    if (canOut < 0) canOut = 0;
    var starved = canOut < to - from;
    // 断流前剩得够多就线性淡出；剩得太少就不淡出，后面接一段指数衰减的尾巴（避免硬切产生爆音）
    var fadeLen = Math.min(FADE, canOut);
    var fadeStart = starved && fadeLen >= 32 ? canOut - fadeLen : Infinity;

    var L = this.L, R = this.R, r = this.r, f = this.frac, env = this.env, g = this.gain;
    var envStep = 1 / FADE;
    for (var i = 0; i < canOut; i++) {
      var i0 = (r - 1) & MASK, i1 = r & MASK, i2 = (r + 1) & MASK, i3 = (r + 2) & MASK;
      var l = catmull(L[i0], L[i1], L[i2], L[i3], f);
      var rr = catmull(R[i0], R[i1], R[i2], R[i3], f);
      if (env < 1) { env += envStep; if (env > 1) env = 1; }
      var e = env;
      if (i >= fadeStart) e *= (canOut - i) / fadeLen;
      outL[from + i] = softclip(l * e * g);
      outR[from + i] = softclip(rr * e * g);
      f += step;
      if (f >= 1) { var k = Math.floor(f); r += k; f -= k; }
    }
    this.r = r; this.frac = f; this.env = env;
    var z = from + canOut;
    if (z < to) {
      var tl = z > 0 ? outL[z - 1] : this.lastL, tr = z > 0 ? outR[z - 1] : this.lastR;
      for (; z < to; z++) { tl *= 0.96; tr *= 0.96; outL[z] = tl; outR[z] = tr; }
    }
    this.lastL = outL[to - 1]; this.lastR = outR[to - 1];
    return from + canOut;
  };

  /* 交叉淡化跳转：旧位置淡出、新位置淡入，渲染 FADE 帧。 */
  PlayerCore.prototype._crossfadeTo = function (outL, outR, newR, step, n) {
    var L = this.L, R = this.R, g = this.gain;
    var r1 = this.r, f1 = this.frac, r2 = newR, f2 = 0, env = this.env;
    var m = Math.min(FADE, n);
    for (var i = 0; i < m; i++) {
      var a = (i + 1) / m;
      var o0 = (r1 - 1) & MASK, o1 = r1 & MASK, o2 = (r1 + 1) & MASK, o3 = (r1 + 2) & MASK;
      var n0 = (r2 - 1) & MASK, n1 = r2 & MASK, n2 = (r2 + 1) & MASK, n3 = (r2 + 2) & MASK;
      var lo = catmull(L[o0], L[o1], L[o2], L[o3], f1), ro = catmull(R[o0], R[o1], R[o2], R[o3], f1);
      var ln = catmull(L[n0], L[n1], L[n2], L[n3], f2), rn = catmull(R[n0], R[n1], R[n2], R[n3], f2);
      outL[i] = softclip(((1 - a) * lo * env + a * ln) * g);
      outR[i] = softclip(((1 - a) * ro * env + a * rn) * g);
      f1 += step; if (f1 >= 1) { var k1 = Math.floor(f1); r1 += k1; f1 -= k1; }
      f2 += step; if (f2 >= 1) { var k2 = Math.floor(f2); r2 += k2; f2 -= k2; }
    }
    this.r = r2; this.frac = f2; this.env = 1;
    this.skips++;
    return m;
  };

  /* 由音频回调调用：填满 outL/outR（Float32Array，等长）。 */
  PlayerCore.prototype.process = function (outL, outR) {
    var n = outL.length;
    var sr = this.srcRate;
    var dt = n / this.dstRate;
    this.clock += dt;
    var target = this.targetSec();
    var level = this.levelSec();
    this.ema += (level - this.ema) * (1 - Math.exp(-dt / EMA_TAU));

    // 长时间稳定、且最差时刻也还有余量，才慢慢收回附加缓冲
    if (this.clock - Math.max(this.lastUnderrunAt, this.lastRelaxAt) > 15) {
      if (this.extraMs > 0 && this.winMin > RELAX_MARGIN) this.extraMs = Math.max(0, this.extraMs - 10);
      this.lastRelaxAt = this.clock;
      this.winMin = Infinity;
    }

    if (this.state === 'buffering') {
      if (level >= target && this.w - this.r >= 4) {
        // 启动/恢复时积压了太多（例如音频上下文启动慢），直接从目标水位开始；反正是从静音淡入，不会有爆音
        if (level - target > 0.02) {
          this.r = this.w - Math.max(4, Math.round(target * sr));
          this.frac = 0;
          level = this.levelSec();
        }
        this.state = 'playing';
        this.env = 0;
        this.ema = level;
        this.playStartAt = this.clock;
      } else {
        var tl = this.lastL, tr = this.lastR;      // 接着上一块的衰减尾巴，平滑归零
        for (var q = 0; q < n; q++) { tl *= 0.96; tr *= 0.96; outL[q] = tl; outR[q] = tr; }
        this.lastL = tl < 1e-6 && tl > -1e-6 ? 0 : tl;
        this.lastR = tr < 1e-6 && tr > -1e-6 ? 0 : tr;
        return;
      }
    }

    // 变速：把平滑后的水位拉回目标
    var adj = (this.ema - target) * KP;
    if (adj > MAXADJ) adj = MAXADJ; else if (adj < -MAXADJ) adj = -MAXADJ;
    this.adj = adj;
    var step = this.ratio0 * (1 + adj);

    var start = 0;
    // 积压太多（网络卡了一下又一次性涌进来 / 刚切到更低延迟档）：交叉淡化跳到目标水位
    var excessEma = this.ema - target, excessNow = level - target;
    var trim = this.forceTrim && excessNow > 0.03;
    this.forceTrim = false;
    if (trim || excessEma > Math.max(0.12, target) || excessNow > 0.4 + target) {
      var newR = this.w - Math.round(target * sr);
      if (newR > this.r + 2 * FADE && this.w - newR >= FADE * step + 8) {
        start = this._crossfadeTo(outL, outR, newR, step, n);
        this.ema = this.levelSec();
      }
    }

    var end = this._render(outL, outR, start, n, step);
    if (end < n) {
      // 断流：进入缓冲状态，等数据攒够再淡入
      this.state = 'buffering';
      this.underruns++;
      if (this.autoAdapt && this.clock - this.playStartAt > 1.5) {
        this.extraMs = Math.min(this.maxExtraMs, this.extraMs + 25);
      }
      this.lastUnderrunAt = this.clock;
      this.winMin = Infinity;
    } else {
      var after = this.levelSec();
      if (after < this.winMin) this.winMin = after;
    }
  };

  PlayerCore.prototype.stats = function () {
    return {
      state: this.state,
      levelMs: this.levelSec() * 1000,
      emaMs: this.ema * 1000,
      targetMs: this.targetMs + this.extraMs,
      adj: this.adj,
      underruns: this.underruns,
      skips: this.skips,
      overflows: this.overflows
    };
  };

  PlayerCore.CAP = CAP;
  root.PlayerCore = PlayerCore;
  if (typeof module !== 'undefined' && module.exports) module.exports = PlayerCore;
})(typeof globalThis !== 'undefined' ? globalThis : this);
