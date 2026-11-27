import { ArrowLeftOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Skeleton } from 'antd';
import { useEffect, useState } from 'react';

import { ApiError, cancelOrder, errorText, listOrders, payOrder } from './api';
import { OrderCard } from './insuranceCards';
import type { AuthSession, Order } from './types';

interface OrdersPageProps {
  session: AuthSession;
  onBack: () => void;
  onSessionExpired: () => void;
  onOpenPolicies: () => void;
}

/**
 * 我的订单：每一次购买行为都留一条记录，包括还没付款的。
 * 待支付是唯一需要用户回来处理的状态，所以和保单分开做成了独立入口。
 */
export function OrdersPage({ session, onBack, onSessionExpired, onOpenPolicies }: OrdersPageProps) {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    listOrders()
      .then((result) => {
        if (alive) setOrders(result);
      })
      .catch((reason) => {
        if (!alive) return;
        if (reason instanceof ApiError && reason.status === 401) {
          onSessionExpired();
          return;
        }
        setError(errorText(reason, '订单加载失败'));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [reloadKey]);

  // 待支付单独计数，摆在标题里提醒用户还有没付完的单
  const unpaid = orders.filter((order) => order.status === 'pending_payment').length;

  // 正在处理的订单 id：用来禁用按钮，防止连点
  const [busyId, setBusyId] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  async function handlePay(order: Order) {
    if (busyId) return;
    setBusyId(order.id);
    setActionError(null);
    try {
      await payOrder(order.id);
      setReloadKey((value) => value + 1);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionExpired();
        return;
      }
      setActionError(errorText(reason, '支付失败，请稍后重试'));
    } finally {
      setBusyId(null);
    }
  }

  async function handleCancel(order: Order) {
    if (busyId) return;
    setBusyId(order.id);
    setActionError(null);
    try {
      await cancelOrder(order.id);
      setReloadKey((value) => value + 1);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionExpired();
        return;
      }
      setActionError(errorText(reason, '取消失败，请稍后重试'));
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="application-page">
      <header className="advisor-header">
        <div className="brand">
          <span className="brand__mark"><SafetyCertificateOutlined /></span>
          <span><strong>安心保</strong><small>我的订单</small></span>
        </div>
        <div>
          <span>{session.user.displayName || session.user.username}</span>
          <Button onClick={onOpenPolicies}>我的保单</Button>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回商城</Button>
        </div>
      </header>

      <main className="application-shell">
        <section className="application-header">
          <span>投保记录</span>
          <h1>
            {loading
              ? '正在加载你的订单'
              : unpaid > 0
                ? `共 ${orders.length} 笔订单，${unpaid} 笔待支付`
                : `共 ${orders.length} 笔订单`}
          </h1>
          <p>一次投保生成一笔订单，支付成功后在同一笔订单里生成保单。</p>
        </section>

        {error ? (
          <Alert
            showIcon
            type="error"
            message="订单加载失败"
            description={error}
            action={<Button onClick={() => setReloadKey((value) => value + 1)}>重新加载</Button>}
          />
        ) : null}

        {actionError ? (
          <Alert showIcon type="error" message={actionError} style={{ marginBottom: 12 }} />
        ) : null}

        {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : null}

        {!loading && !error && orders.length === 0 ? (
          <Empty description="还没有订单。投保流程里点「支付」后会生成订单。" />
        ) : null}

        <div className="policy-card-grid">
          {orders.map((order) => (
            <OrderCard
              order={order}
              key={order.id}
              busy={busyId === order.id}
              onPay={(target) => void handlePay(target)}
              onCancel={(target) => void handleCancel(target)}
            />
          ))}
        </div>
      </main>
    </div>
  );
}
