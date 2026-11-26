import { formatCategoryLabel } from './categoryLabels';
import type { Order, Policy } from './types';

/**
 * 保单卡和订单卡。
 * <p>
 * 两个页面（我的保单 / 我的订单）都用这两张卡，所以抽出来放一处，
 * 改样式只改一次。卡片的 CSS 沿用对话里 policy-card 那套类名，没有新增样式。
 */

/** policies.status 的中文标签 */
const POLICY_STATUS_LABELS: Record<string, string> = {
  active: '保障中',
  pending: '待生效',
  expired: '已到期',
  terminated: '已终止',
};

/** orders.status 的中文标签 */
const ORDER_STATUS_LABELS: Record<string, string> = {
  pending_payment: '待支付',
  pending_policy: '待出单',
  paid: '已支付',
  completed: '已完成',
  cancelled: '已取消',
};

/**
 * 订单状态 → 徽标颜色。复用已有的 policy-status--xxx 样式类，
 * 让订单和保单的徽标长得一样，不额外写 CSS。
 */
const ORDER_STATUS_CLASS: Record<string, string> = {
  pending_payment: 'pending',
  pending_policy: 'pending',
  paid: 'active',
  completed: 'active',
  cancelled: 'terminated',
};

export function PolicyCard({ policy }: { policy: Policy }) {
  const status = String(policy.status ?? 'active');
  return (
    <article className="policy-card">
      <div className="policy-card__top">
        <div>
          <strong>{policy.product_name || '保险产品'}</strong>
          {policy.policy_number ? <span>{policy.policy_number}</span> : null}
        </div>
        <em className={`policy-status policy-status--${status}`}>
          {POLICY_STATUS_LABELS[status] ?? status}
        </em>
      </div>
      <dl>
        <div>
          <dt>险种</dt>
          <dd>{formatCategoryLabel(policy.category)}</dd>
        </div>
        <div>
          <dt>承保公司</dt>
          <dd>{policy.insurer || '—'}</dd>
        </div>
        <div>
          <dt>保障期间</dt>
          <dd>{periodText(policy)}</dd>
        </div>
        <div>
          <dt>起保日期</dt>
          <dd>{dateText(policy.effective_at)}</dd>
        </div>
      </dl>
    </article>
  );
}

export function OrderCard({ order }: { order: Order }) {
  const status = String(order.status ?? 'pending_payment');
  const items = order.items ?? [];
  return (
    <article className="policy-card">
      <div className="policy-card__top">
        <div>
          <strong>{order.order_number || '订单'}</strong>
          <span>{dateText(order.created_at)} 下单</span>
        </div>
        <em className={`policy-status policy-status--${ORDER_STATUS_CLASS[status] ?? 'pending'}`}>
          {ORDER_STATUS_LABELS[status] ?? status}
        </em>
      </div>
      <dl>
        <div>
          <dt>合计保费</dt>
          <dd>¥{moneyOf(order.total_premium)}</dd>
        </div>
        <div>
          <dt>产品数量</dt>
          <dd>{items.length}</dd>
        </div>
        {/* 逐项列出买了什么；product_name 是下单时的快照，产品改名也不影响历史订单 */}
        {items.map((item, index) => (
          <div key={String(item.plan_item_id ?? index)}>
            <dt>{item.product_name || `产品 ${String(item.product_id ?? '')}`}</dt>
            <dd>¥{moneyOf(item.premium)}</dd>
          </div>
        ))}
      </dl>
    </article>
  );
}

/** Java 返回的是 ISO 时间串（2026-11-26T15:20:11），页面上只看日期 */
export function dateText(value: unknown): string {
  if (value === null || value === undefined) return '—';
  const text = String(value);
  return text ? text.slice(0, 10) : '—';
}

export function periodText(policy: Policy): string {
  const start = dateText(policy.effective_at);
  const end = dateText(policy.expires_at);
  if (start === '—' && end === '—') return '—';
  return `${start} 至 ${end}`;
}

export function moneyOf(value: unknown): string {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toLocaleString('zh-CN') : String(value ?? '—');
}
