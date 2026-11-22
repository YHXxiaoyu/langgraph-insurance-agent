import { readSession } from './auth';
import type {
  ChatHistory,
  ChatThread,
  InsuranceCategory,
  LoginResult,
  PlanInterrupt,
  Product,
} from './types';

export const API_BASE = '/api/v1';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

type RawBody = Record<string, unknown> | null;

/**
 * 取当前登录令牌。
 * 没有令牌就直接抛 401 —— 页面上的 isUnauthorized 分支会统一把用户送回登录。
 */
function authHeaders(): Record<string, string> {
  const token = readSession()?.token;
  if (!token) {
    throw new ApiError('登录已过期，请重新登录', 401);
  }
  return { Authorization: `Bearer ${token}` };
}

/**
 * 统一拆掉 Java 的 R<T> 外壳。
 * Java 所有接口都是 {code, msg, data}，业务代码只关心 data；
 * 错误信息也在这里翻译成 ApiError，调用方不用各自解析。
 */
async function unwrap<T>(response: Response): Promise<T> {
  const body = (await response.json().catch(() => null)) as RawBody;

  if (!response.ok) {
    const message =
      typeof body?.msg === 'string'
        ? body.msg
        : typeof body?.message === 'string'
          ? body.message
          : '请求失败，请稍后重试';
    throw new ApiError(message, response.status);
  }
  if (response.status === 204 || body === null) {
    return undefined as T;
  }
  // 有 code + data 就是 R<T> 外壳，拆开再返回
  if (typeof body.code === 'number' && 'data' in body) {
    if (body.code !== 200) {
      throw new ApiError(
        typeof body.msg === 'string' ? body.msg : '请求失败，请稍后重试',
        body.code,
      );
    }
    return body.data as T;
  }
  return body as unknown as T;
}

/** 公开接口：不需要登录，例如产品目录 */
async function publicRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...init?.headers,
    },
  });
  return unwrap<T>(response);
}

/** 需要登录的接口：自动带上 Bearer 令牌 */
async function authorizedRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
      ...init?.headers,
    },
  });
  return unwrap<T>(response);
}

export async function login(username: string, password: string): Promise<LoginResult> {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  return unwrap<LoginResult>(response);
}

export function listProducts(
  category: InsuranceCategory | 'all',
  signal?: AbortSignal,
): Promise<Product[]> {
  const params = new URLSearchParams();
  if (category !== 'all') {
    params.set('category', category);
  }
  const query = params.toString();
  return publicRequest<Product[]>(`/products${query ? `?${query}` : ''}`, { signal });
}

export function markPlanItemInsured(planId: string, itemId: string): Promise<unknown> {
  return authorizedRequest<unknown>(
    `/insurance-plans/${encodeURIComponent(planId)}/items/${encodeURIComponent(itemId)}/insured`,
    { method: 'PATCH' },
  );
}

export function getInsurancePlan(planId: string): Promise<unknown> {
  return authorizedRequest<unknown>(`/insurance-plans/${encodeURIComponent(planId)}`);
}

export function listChatThreads(): Promise<ChatThread[]> {
  return authorizedRequest<ChatThread[]>('/chat-threads');
}

export function createChatThread(title = '保险咨询'): Promise<ChatThread> {
  return authorizedRequest<ChatThread>('/chat-threads', {
    method: 'POST',
    body: JSON.stringify({ title }),
  });
}

export function getChatHistory(threadId: string): Promise<ChatHistory> {
  return authorizedRequest<ChatHistory>(`/chat-threads/${threadId}/messages`);
}

export function renameChatThread(threadId: string, title: string): Promise<ChatThread> {
  return authorizedRequest<ChatThread>(`/chat-threads/${threadId}`, {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  });
}

export async function deleteChatThread(threadId: string): Promise<void> {
  await authorizedRequest<void>(`/chat-threads/${threadId}`, {
    method: 'DELETE',
  });
}

export async function streamChat(
  threadId: string,
  input: { message?: string; decision?: Record<string, unknown> },
  handlers: {
    onMessage: (chunk: string) => void;
    onAdditionalInfo?: (info: unknown) => void;
    onInterrupt?: (interrupt: PlanInterrupt) => void;
    onError?: (message: string) => void;
  },
): Promise<void> {
  const response = await fetch(`${API_BASE}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    // Java 的 ChatRequestDTO 用 @JsonProperty("thread_id") 接这个字段
    body: JSON.stringify({ thread_id: threadId, ...input }),
  });
  if (!response.ok || !response.body) {
    const error = (await response.json().catch(() => null)) as RawBody;
    const message =
      typeof error?.msg === 'string'
        ? error.msg
        : typeof error?.message === 'string'
          ? error.message
          : '消息发送失败，请稍后重试';
    throw new ApiError(message, response.status);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() ?? '';
    parts.forEach((eventText) => handleSseEvent(eventText, handlers));
  }
  if (buffer.trim()) {
    handleSseEvent(buffer, handlers);
  }
}

function handleSseEvent(
  eventText: string,
  handlers: {
    onMessage: (chunk: string) => void;
    onAdditionalInfo?: (info: unknown) => void;
    onInterrupt?: (interrupt: PlanInterrupt) => void;
    onError?: (message: string) => void;
  },
) {
  const lines = eventText.split(/\r?\n/);
  const event = lines.find((line) => line.startsWith('event:'))?.slice(6).trim();
  const data = lines
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n');
  if (!data) return;
  if (event === 'message') {
    handlers.onMessage(parseSseMessage(data));
    return;
  }
  if (event === 'error') {
    // Java 在 Python 不可用时推一条 event:error，以前这里没处理，
    // 前端只会看到气泡一直空着，不知道发生了什么
    handlers.onError?.(parseSseMessage(data));
    return;
  }
  if (event === 'interrupt') {
    const interrupt = parseInterrupt(data);
    if (interrupt) handlers.onInterrupt?.(interrupt);
    return;
  }
  if (event === 'additional_info') {
    handlers.onAdditionalInfo?.(parseJsonLike(data) ?? data);
  }
}

function parseSseMessage(data: string): string {
  try {
    const value = JSON.parse(data) as unknown;
    return typeof value === 'string' ? value : JSON.stringify(value);
  } catch {
    return data;
  }
}

function parseInterrupt(data: string): PlanInterrupt | null {
  const parsed = parseJsonLike(data);
  const value = Array.isArray(parsed) ? parsed[0] : parsed;
  if (!value || typeof value !== 'object') return null;
  const candidate = value as Partial<PlanInterrupt>;
  if (!('plan' in candidate) || typeof candidate.confirm_info !== 'string') return null;
  return {
    plan: candidate.plan,
    confirm_info: candidate.confirm_info,
    action: Array.isArray(candidate.action)
      ? candidate.action.filter((item): item is 'approve' | 'reject' =>
          item === 'approve' || item === 'reject',
        )
      : ['approve', 'reject'],
  };
}

function parseJsonLike(data: string): unknown {
  try {
    return JSON.parse(data);
  } catch {
    return null;
  }
}
