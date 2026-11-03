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
        chapter: this.titles[this.chapterIndex] ? this.titles[this.chapterIndex].title : '',
      }),
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

    window.addEventListener('resize', () => {
      if (settings.scrollMode === 'page') this.measurePages();
    });

    document.addEventListener('visibilitychange', () => {
      if (document.hidden) this.saveProgress();
    });
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
    const p = book.progress || {};
    const idx = Math.min(Math.max(0, p.chapterIndex || 0), this.titles.length - 1);
    await this.loadChapter(idx, { block: p.blockIndex || 0 });
    return true;
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

    requestAnimationFrame(() => {
      if (settings.scrollMode === 'page') {
        this.measurePages();
        if (opt.block) {
          const el = this.el.text.querySelector(`.sent[data-i="${opt.block}"]`);
          if (el) this.page = Math.max(0, Math.round(el.offsetLeft / (this.step || 1)));
          this.goPage(this.page);
        }
      } else if (opt.block) {
        const el = this.el.text.querySelector(`.sent[data-i="${opt.block}"]`);
        if (el) {
          const r = el.getBoundingClientRect(), sr = this.el.scroll.getBoundingClientRect();
          this.el.scroll.scrollTop = Math.max(0, r.top - sr.top + this.el.scroll.scrollTop - 8);
        }
      }
      this.updateProgressBar();
      this.saveProgress();
    });

    if (this.speaker.playing) {
      this.speaker.sentences = this.sentences;
      this.speaker.index = 0;
    }
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

  async saveProgress() {
    if (!this.book || !this.chapter) return;
    const ratio = this.currentRatio();
    const block = this.currentBlock();
    const before = this.charsBefore[this.chapterIndex] || 0;
    const len = this.chapter.len || this.chapter.text.length || 0;
    const read = before + Math.round(len * ratio);
    const total = this.book.chars || this.charsBefore[this.charsBefore.length - 1] || 1;
    const pct = Math.max(0, Math.min(99.9, read / total * 100));
    const patch = {
      progress: {
        chapterIndex: this.chapterIndex,
        blockIndex: block,
        ratio,
        percent: pct,
      },
      lastReadAt: Date.now(),
    };
    Object.assign(this.book, patch);
    try { await db.patchBook(this.book.id, patch); } catch (e) {}
    this.updateProgressBar();
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
    return parsed;
  },
};

/* 自动进入下一章：滚到底部后继续下滑即触发 */
(() => {
  const sc = $('rd-scroll');
  let lastTop = 0, lock = 0;
  sc.addEventListener('scroll', () => {
    if (!settings.autoNext || settings.scrollMode === 'page' || !reader.book) return;
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
