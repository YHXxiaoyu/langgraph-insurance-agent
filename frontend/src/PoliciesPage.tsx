import { ArrowLeftOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Skeleton } from 'antd';
import { useEffect, useState } from 'react';

import { ApiError, errorText, listPolicies } from './api';
import { PolicyCard } from './insuranceCards';
import type { AuthSession, Policy } from './types';

interface PoliciesPageProps {
  session: AuthSession;
  onBack: () => void;
  onSessionExpired: () => void;
  onOpenOrders: () => void;
}

/**
 * 我的保单。
 * 保单本身在支付成功那一刻由 Java 自动发出，这个页面只负责展示。
 * 购买记录在「我的订单」里，两个概念分开，入口也分开。
 */
export function PoliciesPage({ session, onBack, onSessionExpired, onOpenOrders }: PoliciesPageProps) {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    // alive 用来防"请求还没回来页面已经切走"导致的 setState 警告
    let alive = true;
    setLoading(true);
    setError(null);
    listPolicies()
      .then((result) => {
        if (alive) setPolicies(result);
      })
      .catch((reason) => {
        if (!alive) return;
        // 令牌过期统一交给外层清会话、送回登录页
        if (reason instanceof ApiError && reason.status === 401) {
          onSessionExpired();
          return;
        }
        setError(errorText(reason, '保单加载失败'));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [reloadKey]);

  return (
    <div className="application-page">
      <header className="advisor-header">
        <div className="brand">
          <span className="brand__mark"><SafetyCertificateOutlined /></span>
          <span><strong>安心保</strong><small>我的保单</small></span>
        </div>
        <div>
          <span>{session.user.displayName || session.user.username}</span>
          <Button onClick={onOpenOrders}>我的订单</Button>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回商城</Button>
        </div>
      </header>

      <main className="application-shell">
        <section className="application-header">
          <span>保单管理</span>
          <h1>{loading ? '正在加载你的保单' : `共 ${policies.length} 张保单`}</h1>
          <p>投保支付成功后自动出单，保单号、保障期间以这里显示的为准。</p>
        </section>

        {error ? (
          <Alert
            showIcon
            type="error"
            message="保单加载失败"
            description={error}
            action={<Button onClick={() => setReloadKey((value) => value + 1)}>重新加载</Button>}
          />
        ) : null}

        {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : null}

        {!loading && !error && policies.length === 0 ? (
          <Empty description="还没有保单。先去智能顾问那里确认一份方案，支付后保单会出现在这里。" />
        ) : null}

        <div className="policy-card-grid">
          {policies.map((policy) => (
            <PolicyCard policy={policy} key={policy.id} />
          ))}
        </div>
      </main>
    </div>
  );
}
