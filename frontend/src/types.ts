export type InsuranceCategory = 'medical' | 'critical_illness' | 'life' | 'accident';

export interface Product {
  id: number;
  name: string;
  clause_name: string;
  category: InsuranceCategory;
  insurer: string;
  image_url: string | null;
  description: string;
  min_premium: number;
  max_premium: number | null;
  target_group: string;
  highlights: string[];
  status: string;
}

export interface UserSummary {
  id: number;
  username: string;
  email: string;
  displayName: string | null;
}

export interface AuthSession {
  token: string;
  user: UserSummary;
}

/** 登录接口返回的原始结构，Java 侧字段是 snake_case */
export interface LoginResult {
  token: string;
  user_id: number;
  username: string;
}

export interface ChatThread {
  id: string;
  title: string;
  created_at: string;
  updated_at: string;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  additionalInfo?: AdditionalInfo[];
  additional_info?: unknown;
}

export interface ChatHistory {
  thread_id: string;
  messages: ChatMessage[];
  interrupt: PlanInterrupt | null;
  additional_info?: unknown;
}

export interface PlanInterrupt {
  plan: unknown;
  confirm_info: string;
  action: Array<'approve' | 'reject'>;
}

export interface ClauseSource {
  source_id: string;
  clause_name?: string | null;
  document_name?: string | null;
  section_path?: string | string[] | null;
  clause_no?: string | null;
  content?: string | null;
  score?: number | null;
  [key: string]: unknown;
}

export interface AdditionalProduct {
  id?: number | string;
  plan_id?: string | null;
  product_id?: number | string;
  name?: string;
  product_name?: string;
  category?: string;
  image_url?: string | null;
  description?: string | null;
  min_premium?: number | string | null;
  annual_premium_budget?: number | string | null;
  target_group?: string | null;
  highlights?: string[];
  recommendation_reason?: string | null;
  reason?: string | null;
  status?: string | null;
  [key: string]: unknown;
}

export interface AdditionalPolicy {
  id?: string;
  policy_number?: string | null;
  application_no?: string | null;
  status?: string | null;
  effective_at?: string | null;
  expires_at?: string | null;
  holder_name?: string | null;
  insured_name?: string | null;
  insured_id_no_masked?: string | null;
  insured_phone?: string | null;
  coverage_amount?: number | string | null;
  premium_amount?: number | string | null;
  payment_frequency?: string | null;
  product_id?: number | string;
  product?: AdditionalProduct;
  coverage_snapshot?: Record<string, unknown> | null;
  policy_snapshot?: Record<string, unknown> | null;
  [key: string]: unknown;
}

export type AdditionalInfo =
  | { type: 'clause_sources'; sources: ClauseSource[] }
  | { type: 'products'; title: string; products: AdditionalProduct[] }
  | { type: 'policies'; title: string; policies: AdditionalPolicy[] };

/**
 * 保单，字段与 Java 侧 PolicyVO 的 snake_case 一一对应。
 * <p>
 * 与上面的 AdditionalPolicy 区分开：那个是 Agent 在对话里推出来的
 * additional_info 结构（字段很不固定），这个是「我的保单」页面的真实接口数据。
 */
export interface Policy {
  id: string;
  policy_number?: string | null;
  product_id?: number | string | null;
  product_name?: string | null;
  category?: string | null;
  insurer?: string | null;
  status?: string | null;
  effective_at?: string | null;
  expires_at?: string | null;
  created_at?: string | null;
  [key: string]: unknown;
}

/** PATCH .../items/{itemId}/insured 的返回体 */
export interface MarkInsuredResult {
  plan?: unknown;
  policy?: Policy | null;
}

/** 订单项，字段对齐 Java 侧 OrderItem 的 snake_case */
export interface OrderItem {
  plan_id?: string | null;
  plan_item_id?: string | null;
  product_id?: number | string | null;
  product_name?: string | null;
  premium?: number | string | null;
  priority?: number | null;
}

/** 投保订单，字段对齐 Java 侧 OrderVO */
export interface Order {
  id: string;
  order_number?: string | null;
  status?: string | null;
  total_premium?: number | string | null;
  items?: OrderItem[];
  created_at?: string | null;
  updated_at?: string | null;
  [key: string]: unknown;
}
