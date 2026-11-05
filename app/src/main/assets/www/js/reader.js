/**
 * reader.js — 阅读器核心：章节渲染、进度记录与恢复、目录跳转、朗读联动
 */

import { db, newId } from './db.js';
import { settings, saveSettings } from './settings.js';
import { splitSentences } from './parser.js';
import { Speaker } from './tts.js';

const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

export const reader = {
  book: null,
  titles: [],
  charsBefore: [],
  chapterIndex: 0,
  chapter: null,
  sentences: [],
  spans: [],
  speaker: new Speaker(),
  page: 0,
  pages: 1,
  step: 0,
  chromeOn: false,
  onExit: null,
  onTtsState: null,
  onToast: null,

  /* ---------------- 初始化 ---------------- */
  init() {
    this.el = {
      page: $('page-reader'),
      scroll: $('rd-scroll'),
      content: $('rd-content'),
      text: $('rd-text'),
      title: $('rd-chapter-title'),
      bookName: $('rd-book-name'),
      chapName: $('rd-chap-name'),
      top: $('rd-top'),
      bottom: $('rd-bottom'),
      fill: $('rd-progress-fill'),
      endInfo: $('rd-end-info'),
      toast: $('rd-toast'),
    };

    this.speaker.on({
      getSentences: () => this.sentences,
      getMeta: () => ({
        book: this.book ? this.book.title : '',
        bookId: this.book ? this.book.id : '',
        chapter: this.titles[this.chapterIndex] ? this.titles[this.chapterIndex].title : '',
        chapterIndex: this.chapterIndex,
      }),
      collectChapters: (from, count) => this.collectChapters(from, count),
      onRemoteChapter: (i, t) => this.onRemoteChapter(i, t),
      highlight: i => this.highlight(i),
      onChapterEnd: async () => {
        if (this.chapterIndex + 1 < this.titles.length) {
          await this.loadChapter(this.chapterIndex + 1, { toTop: true, auto: true });
          return true;
        }
        return false;
      },
      onState: s => { if (this.onTtsState) this.onTtsState(s); },
      onError: m => { if (this.onToast) this.onToast(m); },
    });

    // 点击正文切换顶/底栏；翻页模式下左右翻页
    this.el.scroll.addEventListener('click', e => {
      if (e.target.closest('button')) return;
      if (settings.scrollMode === 'page') {
        const x = e.clientX, w = window.innerWidth;
        if (x < w * 0.3) return this.goPage(this.page - 1);
        if (x > w * 0.7) return this.goPage(this.page + 1);
      }
      this.toggleChrome();
    });

    let timer = null;
    this.el.scroll.addEventListener('scroll', () => {
      if (settings.scrollMode === 'page') return;
      this.updateProgressBar();
      clearTimeout(timer);
      timer = setTimeout(() => this.saveProgress(), 350);
    }, { passive: true });

    // 用户自己动手了就放弃自动定位，避免"回位"跟用户抢滚动条
    const markUser = () => { this._userScrolled = true; };
    ['touchstart', 'wheel', 'mousedown', 'keydown'].forEach(ev =>
      this.el.scroll.addEventListener(ev, markUser, { passive: true }));

    window.addEventListener('resize', () => {
      if (settings.scrollMode === 'page') this.measurePages();
    });

    // 退出/切后台/被系统杀掉前都存一次
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.saveProgress(true);
      else this.retryRestore();          // 页面真正可见后，若还没回到位就再补一次
    });
    window.addEventListener('pagehide', () => this.saveProgress(true));
    window.addEventListener('beforeunload', () => this.saveProgress(true));
    window.addEventListener('pageshow', () => this.retryRestore());

    // 连点章节标题 3 次 → 阅读位置诊断（真机排查用）
    let taps = 0, tapT = 0;
    this.el.title.addEventListener('click', () => {
      const now = Date.now();
      taps = now - tapT < 900 ? taps + 1 : 1;
      tapT = now;
      if (taps >= 3) { taps = 0; this.showDiag(); }
    });
  },

  /* ---------------- 位置持久化（双写：IndexedDB + localStorage） ---------------- */
  posKey() { return 'nr.pos.' + (this.book ? this.book.id : ''); },

  savePosLocal(p) {
    try {
      localStorage.setItem(this.posKey(), JSON.stringify({
        chapterIndex: p.chapterIndex, blockIndex: p.blockIndex,
        ratio: p.ratio, percent: p.percent, ts: Date.now(),
      }));
    } catch (e) {}
  },

  /** 取较新的那份：localStorage 比数据库新（数据库写失败时）就用它 */
  readPos(book) {
    const p = (book && book.progress) || {};
    const dbTs = (book && book.lastReadAt) || 0;
    let ls = null;
    try { ls = JSON.parse(localStorage.getItem('nr.pos.' + book.id) || 'null'); } catch (e) {}
    // 数据库被写成「第1章开头」但本地备份还留着真实位置：备份优先（重置进度时会 clearPos，不会误伤）
    const dbZero = !p.chapterIndex && !p.blockIndex && !(p.ratio > 0.02);
    if (ls && ls.chapterIndex > 0 && dbZero) {
      this._diagPush('库里是 0,0,0，用本地备份 第' + (ls.chapterIndex + 1) + '章');
      return ls;
    }
    if (ls && ls.ts > dbTs + 500) {
      this._diagPush('用本地备份位置 ts=' + ls.ts + ' > 库 ' + dbTs);
      return ls;
    }
    return p;
  },

  _diagPush(s) {
    this._diag = this._diag || [];
    this._diag.push(s);
    if (this._diag.length > 12) this._diag.shift();
  },

  /** 手动重置进度/重新解析时，本地备份也要一起清掉 */
  clearPos(id) {
    try { localStorage.removeItem('nr.pos.' + id); } catch (e) {}
    // 原生那边落盘的朗读位置同样作废，否则下次又被旧位置顶回去
    try {
      const n = this.speaker && this.speaker.native;
      if (n && n.clearLastPos) n.clearLastPos();
    } catch (e) {}
    this._baseChapter = 0;
  },

  /* ---------------- 打开书籍 ---------------- */
  async open(book) {
    this.book = book;
    this.titles = await db.getTitles(book.id);
    if (!this.titles.length) { this.toast('这本书没有章节数据'); return false; }
    this.charsBefore = [0];
    let acc = 0;
    for (const t of this.titles) { acc += t.len || 0; this.charsBefore.push(acc); }

    this.el.bookName.textContent = book.title;
    this.applyStyle();
    const p = this.readPos(book);
    let idx = Math.min(Math.max(0, p.chapterIndex || 0), this.titles.length - 1);
    let rblock = p.blockIndex || 0;
    let rratio = p.ratio || 0;
    this._baseChapter = idx;

    // 原生队列还活着（App 重进、或网页被系统重载但朗读没停）：
    // 这时朗读进度才是真的，网页里存的那份是冻结前的旧位置。
    const ns = this.speaker && this.speaker.nativeState ? this.speaker.nativeState() : null;
    const live = ns && (ns.playing || ns.paused)
      && ns.chapter >= 0 && ns.chapter < this.titles.length
      && (ns.chapter > idx || (ns.chapter === idx && (ns.index || 0) > rblock));
    if (live) {
      idx = ns.chapter;
      rblock = ns.index || 0;
      rratio = ns.total > 0 ? Math.min(0.999, rblock / ns.total) : 0;
      this._diagPush('用原生朗读位置：第' + (ns.chapter + 1) + '章 ' + (rblock + 1) + '/' + ns.total);
    } else {
      // 队列已经停了（App 被杀过）：原生上次落盘的位置也可能比库里的新
      const lp = this.lastNativePos(book.id);
      if (lp && lp.chapter > idx && lp.chapter < this.titles.length) {
        idx = lp.chapter;
        rblock = lp.index || 0;
        rratio = lp.total > 0 ? Math.min(0.999, rblock / lp.total) : 0;
        this._diagPush('用原生落盘位置：第' + (lp.chapter + 1) + '章 ' + (rblock + 1) + '/' + lp.total);
      }
    }

    await this.loadChapter(idx, { block: rblock, ratio: rratio, restore: true });

    if (live) {
      // 把高亮和播放状态接上，避免页面显示第一句而声音在读别处
      this._ttsBlock = rblock;
      this.speaker.chapter = ns.chapter;
      this.speaker.index = rblock;
      this.speaker.total = ns.total || 0;
      this.speaker.playing = true;
      this.speaker.paused = !!ns.paused;
      this.speaker.sentences = this.sentences;
      this.highlight(rblock);
      this.speaker.emit();
    }
    return true;
  },

  /**
   * 收集 fromChapter 起的 count 章句子，交给原生连续播放（锁屏/后台跨章的核心）。
   * 当前章直接用已切好的 this.sentences，其余章现读现切。
   */
  async collectChapters(fromChapter, count) {
    if (!this.book) return '[]';
    const out = [];
    const end = Math.min(fromChapter + (count || 8), this.titles.length);
    for (let i = fromChapter; i < end; i++) {
      let sents = null;
      if (i === this.chapterIndex && this.sentences.length) {
        sents = this.sentences.slice();
      } else {
        const ch = await db.getChapter(this.book.id, i);
        if (!ch) break;
        sents = [];
        String(ch.text || '').split(/\n+/).map(s => s.trim()).filter(Boolean)
          .forEach(p => { for (const s of splitSentences(p)) sents.push(s); });
      }
      if (!sents.length) break;   // 遇到空章就停，避免章节序号错位
      out.push({
        t: (this.titles[i] && this.titles[i].title) || ('第 ' + (i + 1) + ' 章'),
        s: sents,
      });
    }
    return JSON.stringify(out);
  },

  /** 原生已读到别的章：网页跟随切换（后台期间冻结，回到前台时由 syncFromNative 补） */
  onRemoteChapter(chapter) {
    if (!this.book) return;
    if (chapter === this.chapterIndex) return;
    if (!(chapter >= 0 && chapter < this.titles.length)) return;
    this.loadChapter(chapter, { toTop: true, auto: true, keepIndex: true });
  },

  async loadChapter(index, opt = {}) {
    if (!this.book) return;
    // 手动翻章/跳转时停掉朗读；自动跨章续播（opt.auto）则保持播放
    if (!opt.auto && this.speaker && this.speaker.playing) this.speaker.stop();
    index = Math.max(0, Math.min(index, this.titles.length - 1));
    this.chapterIndex = index;
    const ch = await db.getChapter(this.book.id, index);
    if (!ch) return;
    this.chapter = ch;
    this.sentences = [];
    this.spans = [];

    this.el.title.textContent = ch.title || ('第 ' + (index + 1) + ' 章');
    this.el.chapName.textContent = ch.title || ('第 ' + (index + 1) + ' 章');

    // 逐段、逐句渲染（句子 span 用于朗读高亮与位置记忆）
    let idx = 0;
    const paras = String(ch.text || '').split(/\n+/).map(s => s.trim()).filter(Boolean);
    const html = paras.map(p => {
      const sents = splitSentences(p);
      const inner = sents.map(s => {
        this.sentences.push(s);
        return `<span class="sent" data-i="${idx++}">${esc(s)}</span>`;
      }).join('');
      return `<p>${inner}</p>`;
    }).join('');
    this.el.text.innerHTML = html || '<p style="text-indent:0">（本章无内容）</p>';
    this.spans = Array.from(this.el.text.querySelectorAll('.sent'));

    // 章末信息
    const rest = this.titles.length - index - 1;
    this.el.endInfo.textContent = rest > 0
      ? `本章 ${ch.len || 0} 字 · 还有 ${rest} 章`
      : `本章 ${ch.len || 0} 字 · 已到全书结尾`;

    this.el.scroll.scrollTop = 0;
    this.page = 0;
    this._userScrolled = false;

    const wantBlock = opt.block > 0 ? opt.block : 0;
    const wantRatio = opt.ratio > 0 ? opt.ratio : 0;
    const needRestore = wantBlock > 0 || wantRatio > 0.02 || opt.restore;
    this._want = needRestore ? { block: wantBlock, ratio: wantRatio } : null;
    this._restoreTries = 0;
    this._restoreDone = false;

    if (needRestore) this.restoreTo(wantBlock, wantRatio);
    else requestAnimationFrame(() => { requestAnimationFrame(() => this.afterRender()); });

    if (this.speaker.playing) {
      // 句子表更新为当前显示章；index 由原生进度回调驱动，这里不重置
      this.speaker.sentences = this.sentences;
    }
  },

  /**
   * 回到上次位置。
   * 真机上章节内容长、字体/布局可能还没稳定，一次 scrollTop 赋值会被夹回 0；
   * 部分安卓 WebView 甚至会忽略程序化 scrollTop。所以：
   *   1) 分多轮重试；2) 赋值不生效时让浏览器自己 scrollIntoView；3) 用比例兜底。
   */
  async restoreTo(block, ratio) {
    const sc = this.el.scroll;
    const pm = settings.scrollMode === 'page';
    this._restoring = true;
    const delays = [0, 60, 150, 320, 650];
    let ok = false;

    for (let k = 0; k < delays.length && !ok; k++) {
      if (delays[k]) await new Promise(r => setTimeout(r, delays[k]));
      if (this._userScrolled) { this._diagPush('k' + k + ' 用户已滚动，放弃'); break; }
      if (k === 3 && document.fonts && document.fonts.ready) {
        try { await document.fonts.ready; } catch (e) {}
      }

      if (pm) {
        this.measurePages(true);
        if (this.pages <= 1) { this._diagPush('k' + k + ' 页数未测出'); continue; }
        const el = block > 0 ? this.spans[block] : null;
        let p = el ? Math.round(el.offsetLeft / (this.step || 1)) : 0;
        // offsetLeft 在多列布局里不一定可靠，block 定位失败就用比例
        if ((p <= 0 || p >= this.pages) && ratio > 0.02) p = Math.round(ratio * (this.pages - 1));
        p = Math.max(0, Math.min(this.pages - 1, p));
        this.page = p;
        this.el.content.style.transition = 'none';
        this.el.content.style.transform = `translateX(${-p * this.step}px)`;
        ok = p > 0;
        this._diagPush('k' + k + ' 翻页 p=' + p + '/' + this.pages);
        if (ok) requestAnimationFrame(() => { this.el.content.style.transition = ''; });
      } else {
        const max = sc.scrollHeight - sc.clientHeight;
        if (max <= 0) { this._diagPush('k' + k + ' 高度未就绪'); continue; }
        const el = block > 0 ? this.spans[block] : null;
        let y = 0;
        if (el) {
          const r = el.getBoundingClientRect(), sr = sc.getBoundingClientRect();
          y = r.top - sr.top + sc.scrollTop - 8;
        } else if (ratio > 0.02) {
          y = ratio * max;
        }
        y = Math.max(0, Math.min(max, Math.round(y)));
        sc.scrollTop = y;
        let got = sc.scrollTop;
        if (Math.abs(got - y) > 24) {
          // scrollTop 被忽略：让浏览器自己把目标元素滚到顶部
          if (el) {
            try { el.scrollIntoView({ block: 'start' }); } catch (e) { try { el.scrollIntoView(true); } catch (e2) {} }
            if (sc.scrollTop > 8) sc.scrollTop -= 8;
          } else {
            sc.scrollTop = y;
          }
          got = sc.scrollTop;
        }
        this._diagPush('k' + k + ' 目标=' + y + ' 实到=' + got + ' max=' + Math.round(max));
        ok = y === 0 || Math.abs(got - y) <= 48;
      }
    }

    this._restoring = false;
    this._userScrolled = false;
    this._restoreDone = ok;
    this._diagPush('恢复' + (ok ? '成功' : '失败') + ' 目标block=' + block + ' ratio=' + (ratio || 0).toFixed(3));
    this.afterRender();

    // 兜底：过 800ms 还没到位，再补一次（真机布局可能更慢）
    if (!ok && (this._restoreTries || 0) < 2) {
      this._restoreTries = (this._restoreTries || 0) + 1;
      setTimeout(() => {
        if (!this._restoreDone && !this._userScrolled) this.restoreTo(block, ratio);
      }, 800);
    }
  },

  /** 页面重新可见时，若上次没回到位就再补一次 */
  retryRestore() {
    if (!this._want || this._restoreDone || this._userScrolled) return;
    if ((this._restoreTries || 0) >= 2) return;
    this._restoreTries = (this._restoreTries || 0) + 1;
    this._diagPush('可见后补一次：' + this._restoreTries);
    this.restoreTo(this._want.block, this._want.ratio);
  },

  /** 连点章节标题 3 次触发：把位置相关的数据摊开，方便截图排查 */
  async showDiag() {
    let saved = '读取失败';
    try {
      const b = await db.getBook(this.book.id);
      saved = JSON.stringify(b && b.progress);
    } catch (e) { saved = 'err ' + e.message; }
    let ls = '无';
    try { ls = localStorage.getItem(this.posKey()) || '无'; } catch (e) {}
    const ns = this.speaker && this.speaker.nativeState ? this.speaker.nativeState() : null;
    let wlog = [];
    try { wlog = JSON.parse(localStorage.getItem('nr.wlog') || '[]'); } catch (e) {}
    const sc = this.el.scroll;
    const lines = [
      '【阅读位置诊断 v1.26】',
      '书: ' + (this.book ? this.book.title : '-'),
      '当前: 第' + (this.chapterIndex + 1) + '章 block=' + this.currentBlock(),
      '滚动: top=' + Math.round(sc.scrollTop) + ' / max=' + Math.round(sc.scrollHeight - sc.clientHeight),
      '屏高=' + sc.clientHeight + ' 屏宽=' + sc.clientWidth + ' 模式=' + settings.scrollMode,
      '听书: playing=' + (this.speaker && this.speaker.playing) + ' 读到句=' + (this._ttsBlock == null ? '-' : this._ttsBlock),
      '原生: ' + (ns ? ('第' + (ns.chapter + 1) + '章 ' + ((ns.index || 0) + 1) + '/' + ns.total + ' playing=' + ns.playing + ' paused=' + ns.paused) : '无'),
      '库里存的: ' + saved,
      '本地备份: ' + ls,
      '写进度流水(时间|章|块|来源): ' + (wlog.length ? wlog.slice(-6).join('  ') : '空'),
    ].concat(this._diag || ['（无可重试）']);
    const txt = lines.join('\n');
    try { alert(txt); } catch (e) { this.toast(txt.slice(0, 90)); }
    if (this.onToast) this.onToast('诊断已弹出，可截图');
  },

  afterRender() {
    this.updateProgressBar();
    const w = this._want;
    if (w && (w.block > 0 || w.ratio > 0.02)) {
      const atTop = settings.scrollMode === 'page'
        ? this.page === 0
        : this.el.scroll.scrollTop < 4;
      // 没能回到原位置就别存，避免把已有的位置覆盖成 0
      if (atTop) return;
    }
    this.saveProgress(true);
  },


  /* ---------------- 样式 ---------------- */
  applyStyle() {
    const s = settings;
    const el = this.el.page;
    el.className = 'page active t-' + s.theme;
    el.style.setProperty('--rd-font', s.fontSize + 'px');
    el.style.setProperty('--rd-line', s.lineHeight);
    el.style.setProperty('--rd-pad', s.padding + 'px');
    const fam = s.family === 'serif'
      ? '"Noto Serif SC","Source Han Serif SC","Songti SC",SimSun,serif'
      : s.family === 'kai'
        ? '"Kaiti SC",KaiTi,"STKaiti","Noto Serif SC",serif'
        : '-apple-system,"PingFang SC","Microsoft YaHei",sans-serif';
    this.el.text.style.fontFamily = fam;
    el.querySelector('.rd-chapter-title').style.fontFamily = fam;
    const pm = s.scrollMode === 'page';
    this.el.scroll.classList.toggle('page-mode', pm);
    if (pm) requestAnimationFrame(() => this.measurePages(true));
  },

  measurePages(stay = false) {
    const c = this.el.content;
    const gap = parseFloat(getComputedStyle(c).columnGap) || 0;
    const w = c.clientWidth;
    this.step = w + gap;
    this.pages = Math.max(1, Math.round(c.scrollWidth / this.step));
    if (!stay) this.goPage(Math.min(this.page, this.pages - 1));
  },

  goPage(p) {
    if (settings.scrollMode !== 'page') return;
    if (p < 0) { if (this.chapterIndex > 0) this.loadChapter(this.chapterIndex - 1, { toTop: true }); return; }
    if (p >= this.pages) {
      if (this.chapterIndex + 1 < this.titles.length) this.loadChapter(this.chapterIndex + 1, { toTop: true });
      else this.toast('已经是最后一章');
      return;
    }
    this.page = p;
    this.el.content.style.transition = 'transform .22s ease';
    this.el.content.style.transform = `translateX(${-p * this.step}px)`;
    this.saveProgress();
  },

  /* ---------------- 进度 ---------------- */
  currentRatio() {
    const sc = this.el.scroll;
    if (settings.scrollMode === 'page') return this.pages > 1 ? this.page / (this.pages - 1) : 1;
    const max = sc.scrollHeight - sc.clientHeight;
    if (max <= 0) return 1;
    return Math.min(1, Math.max(0, sc.scrollTop / max));
  },

  currentBlock() {
    if (settings.scrollMode === 'page') {
      const target = this.page * this.step;
      let best = 0;
      for (let i = 0; i < this.spans.length; i++) {
        if (this.spans[i].offsetLeft >= target - 4) { best = i; break; }
      }
      return best;
    }
    const sc = this.el.scroll;
    const top = sc.scrollTop + 8;
    let lo = 0, hi = this.spans.length - 1, res = 0;
    // 二分查找视口顶部所在的句子
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      const t = this.spans[mid].offsetTop;
      if (t <= top) { res = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return res;
  },

  async saveProgress(force) {
    if (!this.book || !this.chapter) return;
    // 正在回位的过程中不要存，否则会把刚恢复的位置写回 0
    if (this._restoring && !force) return;

    let ratio = this.currentRatio();
    let block = this.currentBlock();
    let chIdx = this.chapterIndex;
    let src = 'web';

    // 回位没做完就被要求存（切后台/退出）：存「目标位置」，别存半成品
    if (this._restoring && this._want) {
      block = this._want.block || block;
      ratio = this._want.ratio || ratio;
      src = 'restoring->want';
    }

    // 原生队列才是真位置：后台连播会自己跨章，网页这边还冻在老章上
    const ns = this.speaker && this.speaker.nativeState ? this.speaker.nativeState() : null;
    if (ns && (ns.playing || ns.paused) && ns.chapter >= 0 && ns.chapter < this.titles.length) {
      const nIdx = ns.index || 0;
      if (ns.chapter !== chIdx || nIdx !== block) src = 'native';
      chIdx = ns.chapter;
      block = nIdx;
      ratio = ns.total > 0 ? Math.min(0.999, nIdx / ns.total) : 0;
      this._ttsBlock = nIdx;
    } else if (document.hidden && this.speaker && this.speaker.playing && this._ttsBlock > 0) {
      // 浏览器语音（无原生队列）时，用朗读到的那句做位置
      block = this._ttsBlock;
      ratio = this.spans.length > 1 ? block / (this.spans.length - 1) : ratio;
      src = 'speech';
    }

    // 防回退：绝不用「第 1 章第 0 句」覆盖已有进度（这是位置丢失的典型痕迹）
    // 除非用户刚刚自己主动跳章（目录/上一章/下一章），那种情况要照实存
    const userJustJumped = this._userNavAt && Date.now() - this._userNavAt < 60000;
    if (chIdx === 0 && block === 0 && ratio < 0.01 && (this._baseChapter || 0) > 0 && !userJustJumped) {
      this._diagPush('拦截：拒绝把进度改回第1章开头（原第' + (this._baseChapter + 1) + '章）');
      this._wlog(0, 0, 'blocked');
      return;
    }

    const before = this.charsBefore[chIdx] || 0;
    const len = (chIdx === this.chapterIndex && this.chapter.len)
      || (this.titles[chIdx] && this.titles[chIdx].len) || 0;
    const read = before + Math.round(len * ratio);
    const total = this.book.chars || this.charsBefore[this.charsBefore.length - 1] || 1;
    const pct = Math.max(0, Math.min(99.9, read / total * 100));
    const patch = {
      progress: { chapterIndex: chIdx, blockIndex: block, ratio, percent: pct },
      lastReadAt: Date.now(),
    };
    this._baseChapter = chIdx;
    Object.assign(this.book, patch);
    this.savePosLocal(patch.progress);          // 先写本地备份，数据库失败也不丢
    this._wlog(chIdx, block, src);
    try { await db.patchBook(this.book.id, patch); } catch (e) { this._diagPush('写库失败 ' + (e.message || e)); }
    this.updateProgressBar();
  },

  /** 原生上次落盘的朗读位置（队列已停也能取到；不是这本书或太旧就返回 null） */
  lastNativePos(bookId) {
    try {
      const n = this.speaker && this.speaker.native;
      if (!n || !n.lastPos) return null;
      const p = JSON.parse(n.lastPos() || '{}');
      if (!p || p.book !== bookId) return null;
      if (!(p.chapter >= 0)) return null;
      // 超过 30 天视为过期，避免很久以前听过的位置把现在的进度顶掉
      if (p.ts && Date.now() - p.ts > 30 * 86400000) return null;
      return p;
    } catch (e) { return null; }
  },

  /** 用户主动跳章（目录、上一章/下一章）：短时间内允许进度“往回跳” */
  noteUserNav() { this._userNavAt = Date.now(); },

  /** 写进度流水（环形，20 条），出问题时能看出是哪一步把位置写坏的 */
  _wlog(chIdx, block, src) {
    try {
      const k = 'nr.wlog';
      const arr = JSON.parse(localStorage.getItem(k) || '[]');
      arr.push([Date.now() % 1000000, chIdx + 1, block, src].join('|'));
      while (arr.length > 20) arr.shift();
      localStorage.setItem(k, JSON.stringify(arr));
    } catch (e) {}
  },

  updateProgressBar() {
    const before = this.charsBefore[this.chapterIndex] || 0;
    const len = this.chapter ? (this.chapter.len || 0) : 0;
    const total = (this.book && this.book.chars) || this.charsBefore[this.charsBefore.length - 1] || 1;
    const pct = Math.min(100, (before + len * this.currentRatio()) / total * 100);
    this.el.fill.style.width = pct.toFixed(1) + '%';
  },

  /* ---------------- 朗读高亮 ---------------- */
  highlight(i) {
    if (i >= 0) this._ttsBlock = i;          // 记住朗读到哪一句，后台/不可见时也能存进度
    this.el.text.querySelectorAll('.sent.speaking').forEach(n => n.classList.remove('speaking'));
    if (i < 0 || !this.spans[i]) return;
    const el = this.spans[i];
    el.classList.add('speaking');
    if (settings.follow) {
      if (settings.scrollMode === 'page') {
        const p = Math.max(0, Math.round(el.offsetLeft / (this.step || 1)));
        if (p !== this.page) this.goPage(p);
      } else {
        const r = el.getBoundingClientRect();
        const sr = this.el.scroll.getBoundingClientRect();
        if (r.top < sr.top + 40 || r.bottom > sr.bottom - 60) {
          this.el.scroll.scrollTop = r.top - sr.top + this.el.scroll.scrollTop - sr.height * 0.35;
        }
      }
    }
  },

  /* ---------------- 交互 ---------------- */
  toggleChrome(force) {
    const on = force === undefined ? !this.chromeOn : force;
    this.chromeOn = on;
    this.el.top.classList.toggle('show', on);
    this.el.bottom.classList.toggle('show', on);
    this.el.scroll.classList.toggle('pad-mode', !on);
  },

  toast(msg) {
    if (this.onToast) return this.onToast(msg);
    const t = this.el.toast;
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(this._tt);
    this._tt = setTimeout(() => t.classList.remove('show'), 1600);
  },

  /* ---------------- 导入后的章节写入 ---------------- */
  async saveBook(parsed, file, opts = {}) {
    const id = opts.id || newId();
    const chapters = parsed.chapters;
    const chars = chapters.reduce((a, c) => a + c.text.length, 0);
    const book = {
      id,
      title: parsed.title,
      author: parsed.author || '',
      format: parsed.format,
      encoding: parsed.encoding || '',
      cover: parsed.cover || null,
      color: opts.color !== undefined ? opts.color : Math.floor(Math.random() * 6),
      chapterCount: chapters.length,
      chars,
      size: file ? file.size : 0,
      fileName: file ? file.name : '',
      createdAt: opts.createdAt || Date.now(),
      lastReadAt: Date.now(),
      progress: { chapterIndex: 0, blockIndex: 0, ratio: 0, percent: 0 },
    };
    await db.putBook(book);
    await db.putChapters(id, chapters);
    if (file) { try { await db.putRaw(id, file); } catch (e) {} }
    return book;
  },

  async reparse(bookId, encoding) {
    const raw = await db.getRaw(bookId);
    if (!raw || !raw.file) throw new Error('原始文件已不存在，请重新导入');
    const { parseFile } = await import('./parser.js');
    const parsed = await parseFile(raw.file, { encoding });
    const chars = parsed.chapters.reduce((a, c) => a + c.text.length, 0);
    await db.putChapters(bookId, parsed.chapters);
    await db.patchBook(bookId, {
      chapterCount: parsed.chapters.length,
      chars,
      encoding: parsed.encoding || encoding,
      progress: { chapterIndex: 0, blockIndex: 0, ratio: 0, percent: 0 },
    });
    this.clearPos(bookId);
    this._baseChapter = 0;    // 重新分章后章节号全变了，防回退基准重置
    return parsed;
  },
};

/* 自动进入下一章：滚到底部后继续下滑即触发 */
(() => {
  const sc = $('rd-scroll');
  let lastTop = 0, lock = 0, lastChapter = -1;
  sc.addEventListener('scroll', () => {
    if (!settings.autoNext || settings.scrollMode === 'page' || !reader.book) return;
    // 程序在恢复上次位置，不是用户在往下滑，别误判成"读到底了"
    if (reader._restoring) { lastTop = sc.scrollTop; return; }
    // 换章后的第一次滚动不判断方向
    if (reader.chapterIndex !== lastChapter) { lastChapter = reader.chapterIndex; lastTop = sc.scrollTop; return; }
    const max = sc.scrollHeight - sc.clientHeight;
    if (max <= 0) return;
    const bottomGap = max - sc.scrollTop;
    const down = sc.scrollTop > lastTop;
    lastTop = sc.scrollTop;
    if (bottomGap < 8 && down && Date.now() - lock > 1200) {
      if (reader.chapterIndex + 1 < reader.titles.length) {
        lock = Date.now();
        reader.toast('正在进入下一章…');
        reader.loadChapter(reader.chapterIndex + 1, { toTop: true });
      }
    }
  }, { passive: true });
})();
