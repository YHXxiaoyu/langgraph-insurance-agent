import type { AuthSession } from './types';

// v3：从「本地假登录」换成真 JWT，旧缓存里没有 token，必须作废
const STORAGE_KEY = 'insurance-mall-auth-v3';

export function readSession(): AuthSession | null {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value ? (JSON.parse(value) as AuthSession) : null;
  } catch {
    window.localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

export function saveSession(session: AuthSession): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  window.localStorage.removeItem(STORAGE_KEY);
}
