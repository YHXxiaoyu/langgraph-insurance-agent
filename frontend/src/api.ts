import { readSession } from './auth';
import type {
  ChatHistory,
  ChatThread,
  Claim,
  InsuranceCategory,
  LoginResult,
  Order,
  PlanInterrupt,
  Policy,
  PremiumQuote,
  PricingForm,
  Product,
} from './types';

export const API_BASE = '/api/v1';

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    /** 后端链路 ID，用户报错时把这串发过来就能 grep 到整条日志 */
    readonly traceId?: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/** 从响应头取后端链路 ID；Java 的 TraceIdFilter 在每个响应上都写了这个头 */
function traceIdOf(response: Response): string | undefined {
  return response.headers.get('x-trace-id') ?? undefined;
}

/**
 * 把任意异常转成展示给用户的一句话。
 * 有 traceId 就附在后面——用户截图发过来，运维直接按这串查日志。
 */
export function errorText(reason: unknown, fallback: string): string {
  const message = reason instanceof Error ? reason.message : fallback;
  const traceId = reason instanceof ApiError ? reason.traceId : undefined;
  return traceId ? `${message}（错误编号 ${traceId}）` : message;
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
    throw new ApiError(message, response.status, traceIdOf(response));
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
        traceIdOf(response),
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

/** 产品分页结果，字段名与 Java 侧 ProductPageVO 的 snake_case 对齐 */
export interface ProductPage {
  items: Product[];
  page: number;
  page_size: number;
  total: number;
  pages: number;
}

export function listProducts(
  category: InsuranceCategory | 'all',
  page: number,
  pageSize: number,
  signal?: AbortSignal,
): Promise<ProductPage> {
  const params = new URLSearchParams();
  if (category !== 'all') {
    params.set('category', category);
  }
  params.set('page', String(page));
  params.set('pageSize', String(pageSize));
  return publicRequest<ProductPage>(`/products?${params.toString()}`, { signal });
}

export function getInsurancePlan(planId: string): Promise<unknown> {
  return authorizedRequest<unknown>(`/insurance-plans/${encodeURIComponent(planId)}`);
}

/** 我的保单，Java 侧已按生效时间倒序并做了到期状态懒更新 */
export function listPolicies(): Promise<Policy[]> {
  return authorizedRequest<Policy[]>('/policies');
}

/**
 * 下单：只传方案和要投保的明细 id，保费由 Java 从方案里读，前端不做定价。
 * 同一方案同一批明细重复调用会返回同一张订单（Java 侧有幂等键）。
 */
export function createOrder(input: {
  planId: string;
  itemIds: string[];
  threadId?: string;
  /** 试算结果 id：带上它，订单金额就按试算价算，而不是方案项的预算价 */
  quoteId?: string;
}): Promise<Order> {
  return authorizedRequest<Order>('/orders', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** 支付：订单转已支付，Java 同事务里发保单 + 把方案项标记已投保 */
export function payOrder(orderId: string): Promise<Order> {
  return authorizedRequest<Order>(`/orders/${encodeURIComponent(orderId)}/pay`, {
    method: 'POST',
  });
}

/** 我的订单，按下单时间倒序 */
export function listOrders(): Promise<Order[]> {
  return authorizedRequest<Order[]>('/orders');
}

/**
 * 取消订单。只有待支付的订单能取消；取消后幂等键会被释放，
 * 用户可以重新下单（重新试算后价格会更新）。
 */
export function cancelOrder(orderId: string): Promise<Order> {
  return authorizedRequest<Order>(`/orders/${encodeURIComponent(orderId)}/cancel`, {
    method: 'POST',
  });
}

/** 我的理赔记录，按提交时间倒序 */
export function listClaims(): Promise<Claim[]> {
  return authorizedRequest<Claim[]>('/claims');
}

/**
 * 提交理赔申请。保单必须是本人的、保障中的，
 * 否则 Java 会直接拒绝（不属于我的保单返回 404）。
 */
export function createClaim(input: {
  policyId: string;
  claimType: string;
  amount?: number;
  description?: string;
}): Promise<Claim> {
  return authorizedRequest<Claim>('/claims', {
    method: 'POST',
    body: JSON.stringify(input),
  });
}

/** 试算表单定义：哪些字段、默认值、选项，全由后端给 */
export function getPricingForm(productId: string): Promise<PricingForm> {
  return authorizedRequest<PricingForm>(
    `/products/${encodeURIComponent(productId)}/pricing-form`,
  );
}

/**
 * 试算保费。结果会落库并带 24 小时有效期，
 * 返回里的 breakdown.explain 是给用户看的算式，一行一条。
 */
export function createPremiumQuote(input: {
  productId: string;
  age: number;
  gender: string;
  coverageAmount: number;
  socialSecurity?: string;
  occupationClass?: string;
}): Promise<PremiumQuote> {
  return authorizedRequest<PremiumQuote>('/premium-quotes', {
    method: 'POST',
    body: JSON.stringify(input),
  });
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
  const traceId = traceIdOf(response);
  if (!response.ok || !response.body) {
    const error = (await response.json().catch(() => null)) as RawBody;
    const message =
      typeof error?.msg === 'string'
        ? error.msg
        : typeof error?.message === 'string'
          ? error.message
          : '消息发送失败，请稍后重试';
    throw new ApiError(message, response.status, traceId);
  }

  // SSE 里的 event:error（Python 侧模型全挂）同样要带上 traceId，
  // 否则用户只能看到"模型服务暂时不可用"，没法定位到具体是哪次请求
  const sseHandlers = traceId
    ? {
        ...handlers,
        onError: (message: string) => handlers.onError?.(`${message}（错误编号 ${traceId}）`),
      }
    : handlers;

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split(/\r?\n\r?\n/);
    buffer = parts.pop() ?? '';
    parts.forEach((eventText) => handleSseEvent(eventText, sseHandlers));
  }
  if (buffer.trim()) {
    handleSseEvent(buffer, sseHandlers);
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
