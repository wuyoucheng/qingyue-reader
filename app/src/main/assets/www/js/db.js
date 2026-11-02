/**
 * db.js — IndexedDB 存储层
 * books    书目与阅读进度
 * chapters 章节正文（复合主键 [bookId, index]）
 * raws     原始文件（用于更换编码后重新分章）
 */

const DB_NAME = 'novel-reader';
const DB_VER = 1;
let _db = null;

export function openDB() {
  if (_db) return Promise.resolve(_db);
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VER);
    req.onupgradeneeded = e => {
      const db = req.result;
      if (!db.objectStoreNames.contains('books')) {
        const s = db.createObjectStore('books', { keyPath: 'id' });
        s.createIndex('lastReadAt', 'lastReadAt');
      }
      if (!db.objectStoreNames.contains('chapters')) {
        const s = db.createObjectStore('chapters', { keyPath: ['bookId', 'index'] });
        s.createIndex('byBook', 'bookId');
      }
      if (!db.objectStoreNames.contains('raws')) {
        db.createObjectStore('raws', { keyPath: 'bookId' });
      }
    };
    req.onsuccess = () => { _db = req.result; resolve(_db); };
    req.onerror = () => reject(req.error);
  });
}

function tx(store, mode = 'readonly') {
  return openDB().then(db => db.transaction(store, mode).objectStore(store));
}

const wrap = req => new Promise((res, rej) => {
  req.onsuccess = () => res(req.result);
  req.onerror = () => rej(req.error);
});

export const db = {
  async allBooks() {
    const s = await tx('books');
    const list = await wrap(s.getAll());
    return list;
  },
  async getBook(id) {
    const s = await tx('books');
    return wrap(s.get(id));
  },
  async putBook(book) {
    const s = await tx('books', 'readwrite');
    return wrap(s.put(book));
  },
  async patchBook(id, patch) {
    const s = await tx('books', 'readwrite');
    const b = await wrap(s.get(id));
    if (!b) return null;
    Object.assign(b, patch);
    return wrap(s.put(b));
  },
  async deleteBook(id) {
    const d = await openDB();
    return new Promise((res, rej) => {
      const t = d.transaction(['books', 'chapters', 'raws'], 'readwrite');
      t.objectStore('books').delete(id);
      t.objectStore('raws').delete(id);
      const idx = t.objectStore('chapters').index('byBook');
      const cur = idx.openKeyCursor(IDBKeyRange.only(id));
      cur.onsuccess = () => {
        const c = cur.result;
        if (c) { t.objectStore('chapters').delete(c.primaryKey); c.continue(); }
      };
      t.oncomplete = () => res(true);
      t.onerror = () => rej(t.error);
    });
  },

  async putChapters(bookId, chapters) {
    const d = await openDB();
    return new Promise((res, rej) => {
      const t = d.transaction('chapters', 'readwrite');
      const s = t.objectStore('chapters');
      const idx = s.index('byBook');
      const del = idx.openKeyCursor(IDBKeyRange.only(bookId));
      del.onsuccess = () => {
        const c = del.result;
        if (c) { s.delete(c.primaryKey); c.continue(); }
        else {
          chapters.forEach((c2, i) => {
            s.put({ bookId, index: i, title: c2.title, text: c2.text, len: c2.text.length });
          });
        }
      };
      t.oncomplete = () => res(chapters.length);
      t.onerror = () => rej(t.error);
    });
  },
  async getChapter(bookId, index) {
    const s = await tx('chapters');
    return wrap(s.get([bookId, index]));
  },
  async getTitles(bookId) {
    const d = await openDB();
    return new Promise((res, rej) => {
      const t = d.transaction('chapters', 'readonly');
      const idx = t.objectStore('chapters').index('byBook');
      const out = [];
      const cur = idx.openCursor(IDBKeyRange.only(bookId));
      cur.onsuccess = () => {
        const c = cur.result;
        if (c) { out.push({ index: c.value.index, title: c.value.title, len: c.value.len || 0 }); c.continue(); }
        else res(out.sort((a, b) => a.index - b.index));
      };
      cur.onerror = () => rej(cur.error);
    });
  },

  async putRaw(bookId, file) {
    const s = await tx('raws', 'readwrite');
    return wrap(s.put({ bookId, file, name: file.name, size: file.size, type: file.type }));
  },
  async getRaw(bookId) {
    const s = await tx('raws');
    return wrap(s.get(bookId));
  },
  async delRaw(bookId) {
    const s = await tx('raws', 'readwrite');
    return wrap(s.delete(bookId));
  },
};

export async function storageInfo() {
  if (navigator.storage && navigator.storage.estimate) {
    try {
      const { usage, quota } = await navigator.storage.estimate();
      const mb = n => (n / 1048576).toFixed(1) + ' MB';
      return `已占用 ${mb(usage)} / 可用约 ${mb(quota)}`;
    } catch (e) { return ''; }
  }
  return '';
}

export function newId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
}
