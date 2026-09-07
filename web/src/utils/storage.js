/**
 * 客户端三轨多层容灾存储 (localStorage + IndexedDB + Cookie 回填)
 *
 * 彻底解决 iOS WebKit ITP / PWA 发版更新导致的 localStorage 静默清空问题。
 */

const DB_NAME = 'xiaoxi_storage'
const DB_VERSION = 1
const STORE_NAME = 'keyval'

export const STORAGE_KEYS = {
  TOKEN: 'xiaoxi-token',
  SESSION: 'xiaoxi-session',
  GREETED: 'xiaoxi-greeted',
}

let dbPromise = null

function getDB() {
  if (typeof window === 'undefined' || !window.indexedDB) {
    return Promise.resolve(null)
  }
  if (!dbPromise) {
    dbPromise = new Promise((resolve) => {
      try {
        const req = window.indexedDB.open(DB_NAME, DB_VERSION)
        req.onupgradeneeded = (e) => {
          const db = e.target.result
          if (!db.objectStoreNames.contains(STORE_NAME)) {
            db.createObjectStore(STORE_NAME)
          }
        }
        req.onsuccess = (e) => resolve(e.target.result)
        req.onerror = () => resolve(null)
      } catch {
        resolve(null)
      }
    })
  }
  return dbPromise
}

export async function idbGet(key) {
  try {
    const db = await getDB()
    if (!db) return null
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const req = store.get(key)
      req.onsuccess = () => resolve(req.result ?? null)
      req.onerror = () => resolve(null)
    })
  } catch {
    return null
  }
}

export async function idbSet(key, value) {
  try {
    const db = await getDB()
    if (!db) return
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const req = store.put(value, key)
      req.onsuccess = () => resolve(true)
      req.onerror = () => resolve(false)
    })
  } catch {
    // 降级静默
  }
}

export async function idbDelete(key) {
  try {
    const db = await getDB()
    if (!db) return
    return new Promise((resolve) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const req = store.delete(key)
      req.onsuccess = () => resolve(true)
      req.onerror = () => resolve(false)
    })
  } catch {
    // 降级静默
  }
}

/**
 * 同步读 localStorage，降级安全
 */
export function getLocalItem(key) {
  try {
    return localStorage.getItem(key) || ''
  } catch {
    return ''
  }
}

/**
 * 双写：同步写入 localStorage + 异步镜像写入 IndexedDB
 */
export function setResilientItem(key, value) {
  try {
    if (value !== undefined && value !== null && value !== '') {
      localStorage.setItem(key, String(value))
      idbSet(key, String(value)).catch(() => {})
    } else {
      localStorage.removeItem(key)
      idbDelete(key).catch(() => {})
    }
  } catch {
    // localStorage 受限时仅尝试写 IndexedDB
    if (value !== undefined && value !== null && value !== '') {
      idbSet(key, String(value)).catch(() => {})
    } else {
      idbDelete(key).catch(() => {})
    }
  }
}

/**
 * 双删：同步删除 localStorage + 异步删除 IndexedDB
 */
export function removeResilientItem(key) {
  try {
    localStorage.removeItem(key)
  } catch {
    // 忽略
  }
  idbDelete(key).catch(() => {})
}

/**
 * 启动时自愈回填：如果 localStorage 为空，尝试从 IndexedDB 唤醒并回填
 */
export async function initResilientStorage() {
  const result = {
    tokenRestored: false,
    sessionRestored: false,
  }

  // 1. 检查并回填 Token
  try {
    const localToken = getLocalItem(STORAGE_KEYS.TOKEN).trim()
    if (!localToken) {
      const idbToken = await idbGet(STORAGE_KEYS.TOKEN)
      if (idbToken && typeof idbToken === 'string' && idbToken.trim()) {
        try {
          localStorage.setItem(STORAGE_KEYS.TOKEN, idbToken.trim())
          result.tokenRestored = true
        } catch {}
      }
    }
  } catch {}

  // 2. 检查并回填 Session
  try {
    const localSession = getLocalItem(STORAGE_KEYS.SESSION).trim()
    if (!localSession) {
      const idbSession = await idbGet(STORAGE_KEYS.SESSION)
      if (idbSession && typeof idbSession === 'string' && idbSession.trim()) {
        try {
          localStorage.setItem(STORAGE_KEYS.SESSION, idbSession.trim())
          result.sessionRestored = true
        } catch {}
      }
    }
  } catch {}

  // 3. 检查并回填 Greeted 记账
  try {
    const localGreeted = getLocalItem(STORAGE_KEYS.GREETED).trim()
    if (!localGreeted) {
      const idbGreeted = await idbGet(STORAGE_KEYS.GREETED)
      if (idbGreeted && typeof idbGreeted === 'string' && idbGreeted.trim()) {
        try {
          localStorage.setItem(STORAGE_KEYS.GREETED, idbGreeted.trim())
        } catch {}
      }
    }
  } catch {}

  return result
}
