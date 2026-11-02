/**
 * tts.js — 系统语音朗读（Web Speech API）
 * 逐句朗读以规避 Chrome 长文本中断问题，支持高亮跟随、跨章续播、定时关闭、屏幕常亮
 */

const synth = window.speechSynthesis;

/** 是否运行在安卓 APP 内（由 WebView 注入原生朗读桥接） */
export const isNativeApp = () => typeof window !== 'undefined' && !!window.AndroidTTS;

export class Speaker {
  constructor() {
    this.supported = !!synth || isNativeApp();
    this.native = isNativeApp() ? window.AndroidTTS : null;
    this.voices = [];
    this.playing = false;
    this.paused = false;
    this.sentences = [];
    this.index = 0;
    this.token = 0;
    this.wakeLock = null;
    this.timerMin = 0;
    this.timerEndsAt = 0;
    this.timerId = null;

    this.hooks = {
      getSentences: () => [],       // () => string[]
      highlight: () => {},          // (index) => void
      onChapterEnd: async () => false, // 返回 true 表示已切到下一章并可继续
      onState: () => {},            // ({playing,paused,text,index,total})
      onError: () => {},
    };
    if (this.native) {
      window.__ttsEvent = (type, id) => this.onNativeEvent(type, id);
    }
    this.loadVoices();
    if (!this.native && this.supported && typeof synth.onvoiceschanged !== 'undefined') {
      synth.onvoiceschanged = () => this.loadVoices();
    }
  }

  /** 原生语音完成回调：done/error 时推进到下一句 */
  onNativeEvent(type, id) {
    if (!this.playing || this.paused) return;
    if (String(id) !== String(this.token)) return;
    if (type === 'done' || type === 'error') {
      setTimeout(() => {
        if (String(id) === String(this.token) && this.playing && !this.paused) this.speakAt(this.index + 1);
      }, 30);
    }
  }

  loadVoices() {
    if (this.native) return [];
    if (!this.supported) return [];
    const list = synth.getVoices() || [];
    if (list.length) this.voices = list;
    return this.voices;
  }

  /** 中文音色优先排序 */
  voiceOptions() {
    if (this.native) {
      try {
        const arr = JSON.parse(this.native.getVoices() || '[]');
        return arr.map(v => ({ voiceURI: v.name, name: v.name, lang: v.locale, localService: true }));
      } catch (e) { return []; }
    }
    const vs = this.voices.length ? this.voices : this.loadVoices();
    const score = v => {
      const l = (v.lang || '').toLowerCase();
      if (/^zh|^cmn/.test(l)) return l.includes('cn') || l.includes('hans') ? 0 : 1;
      if (/^yue/.test(l)) return 2;
      return 9;
    };
    return vs.slice().sort((a, b) => score(a) - score(b));
  }

  findVoice(uri) {
    if (!uri) return null;
    return this.voiceOptions().find(v => v.voiceURI === uri) || null;
  }

  on(events) { Object.assign(this.hooks, events); return this; }

  /** 从指定句开始朗读当前章节 */
  async play(startIndex = -1, opt = {}) {
    if (!this.supported) { this.hooks.onError('当前浏览器不支持语音朗读'); return; }
    this.sentences = this.hooks.getSentences() || [];
    if (!this.sentences.length) { this.hooks.onError('本章没有可朗读的内容'); return; }
    if (startIndex >= 0) this.index = Math.min(startIndex, this.sentences.length - 1);
    if (this.index >= this.sentences.length) this.index = 0;

    this.playing = true;
    this.paused = false;
    this.opt = opt;
    await this.requestWake(opt.keepAwake);
    this.setTimer(opt.timerMin || 0);
    this.speakAt(this.index);
  }

  speakAt(i) {
    if (!this.playing) return;
    if (i >= this.sentences.length) { this.nextChapter(); return; }
    this.index = i;
    const text = this.sentences[i];
    this.hooks.highlight(i);
    this.emit();

    if (!text || !text.trim()) { setTimeout(() => this.speakAt(i + 1), 0); return; }

    const my = ++this.token;
    const voiceURI = this.opt && this.opt.voiceURI;

    // 安卓 APP：走原生 TextToSpeech
    if (this.native) {
      this.native.setRate(this.opt && this.opt.rate ? this.opt.rate : 1);
      this.native.setPitch(this.opt && this.opt.pitch ? this.opt.pitch : 1);
      if (voiceURI) this.native.setVoice(voiceURI);
      this.native.speak(text, String(my));
      return;
    }

    const u = new SpeechSynthesisUtterance(text);
    const v = this.findVoice(voiceURI);
    if (v) { u.voice = v; u.lang = v.lang; } else { u.lang = (this.opt && this.opt.lang) || 'zh-CN'; }
    u.rate = this.opt && this.opt.rate ? this.opt.rate : 1;
    u.pitch = this.opt && this.opt.pitch ? this.opt.pitch : 1;
    u.volume = 1;

    const my = ++this.token;
    u.onend = () => {
      if (my !== this.token || !this.playing) return;
      setTimeout(() => { if (my === this.token && this.playing && !this.paused) this.speakAt(i + 1); }, 30);
    };
    u.onerror = e => {
      if (my !== this.token) return;
      if (e && e.error === 'interrupted') return;   // 用户主动停止
      setTimeout(() => { if (my === this.token && this.playing && !this.paused) this.speakAt(i + 1); }, 100);
    };
    try { synth.speak(u); } catch (e) { this.hooks.onError('语音朗读启动失败'); this.stop(); }
  }

  /** 播放中动态修改语速/音色/定时：立即用新参数重读当前句 */
  setOpt(patch) {
    this.opt = Object.assign({}, this.opt || {}, patch);
    if ('timerMin' in patch) this.setTimer(patch.timerMin);
    if (this.playing && !this.paused) {
      this.token++;
      if (this.native) this.native.stop(); else { try { synth.cancel(); } catch (e) {} }
      setTimeout(() => this.speakAt(this.index), 80);
    }
    this.emit();
  }

  pause() {
    if (!this.playing || this.paused) return;
    this.paused = true;
    if (this.native) this.native.stop();
    else { try { synth.pause(); } catch (e) {} }
    this.emit();
  }

  resume() {
    if (!this.playing || !this.paused) return;
    this.paused = false;
    if (this.native) {
      this.speakAt(this.index);          // 原生 TTS 无 resume，重读当前句
    } else {
      try { synth.resume(); } catch (e) {
        // 某些安卓机型 resume 无效，直接重读当前句
        this.speakAt(this.index);
      }
    }
    this.emit();
  }

  toggle() { this.paused ? this.resume() : this.pause(); }

  stop(keepIndex = true) {
    this.playing = false;
    this.paused = false;
    this.token++;
    if (this.native) this.native.stop();
    else { try { synth.cancel(); } catch (e) {} }
    this.clearTimer();
    this.releaseWake();
    if (!keepIndex) this.index = 0;
    this.hooks.highlight(-1);
    this.emit();
  }

  next() {
    if (!this.playing) return;
    this.token++;
    if (this.native) this.native.stop(); else { try { synth.cancel(); } catch (e) {} }
    setTimeout(() => this.speakAt(Math.min(this.index + 1, this.sentences.length - 1)), 40);
  }

  prev() {
    if (!this.playing) return;
    this.token++;
    if (this.native) this.native.stop(); else { try { synth.cancel(); } catch (e) {} }
    setTimeout(() => this.speakAt(Math.max(this.index - 1, 0)), 40);
  }

  async nextChapter() {
    const moved = await this.hooks.onChapterEnd();
    if (moved) {
      this.sentences = this.hooks.getSentences() || [];
      this.index = 0;
      setTimeout(() => this.speakAt(0), 120);
    } else {
      this.stop();
    }
  }

  /* --- 定时关闭 --- */
  setTimer(min) {
    this.clearTimer();
    this.timerMin = min || 0;
    if (!min) { this.timerEndsAt = 0; this.emit(); return; }
    this.timerEndsAt = Date.now() + min * 60000;
    this.timerId = setInterval(() => {
      if (Date.now() >= this.timerEndsAt) {
        this.stop();
        this.hooks.onError('定时关闭时间已到，朗读停止');
      } else this.emit();
    }, 1000);
  }
  clearTimer() { if (this.timerId) { clearInterval(this.timerId); this.timerId = null; } }
  timerLeftText() {
    if (!this.timerEndsAt) return '';
    const s = Math.max(0, Math.round((this.timerEndsAt - Date.now()) / 1000));
    const m = Math.floor(s / 60);
    return `将在 ${m}:${String(s % 60).padStart(2, '0')} 后自动停止（朗读中）`;
  }

  /* --- 屏幕常亮 --- */
  async requestWake(on) {
    if (this.native) { this.native.keepScreenOn(!!on); return; }
    this.releaseWake();
    if (!on || !('wakeLock' in navigator)) return;
    try { this.wakeLock = await navigator.wakeLock.request('screen'); } catch (e) { this.wakeLock = null; }
  }
  releaseWake() {
    if (this.native) { this.native.keepScreenOn(false); return; }
    try { if (this.wakeLock) this.wakeLock.release(); } catch (e) {}
    this.wakeLock = null;
  }

  emit() {
    this.hooks.onState({
      playing: this.playing,
      paused: this.paused,
      index: this.index,
      total: this.sentences.length,
      text: this.playing ? (this.sentences[this.index] || '') : '',
      timer: this.timerLeftText(),
    });
  }
}

export function ttsSupported() {
  return isNativeApp() || (!!window.speechSynthesis && typeof SpeechSynthesisUtterance !== 'undefined');
}
