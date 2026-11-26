import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  FileTextOutlined,
  HeartOutlined,
  IdcardOutlined,
  PayCircleOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Alert, Button, Steps } from 'antd';
import { useState } from 'react';

import { ApiError, createOrder, errorText, payOrder } from './api';
import type { AuthSession, Order } from './types';

interface ApplicationPageProps {
  session: AuthSession;
  onBack: () => void;
  onSessionExpired: () => void;
  onOpenPolicies: () => void;
}

const APPLICATION_STEPS = [
  { title: '保费试算', icon: <PayCircleOutlined /> },
  { title: '客户告知', icon: <FileTextOutlined /> },
  { title: '健康告知', icon: <HeartOutlined /> },
  { title: '保单信息', icon: <IdcardOutlined /> },
  { title: '确认支付', icon: <CheckCircleOutlined /> },
];

export function ApplicationPage({
  session,
  onBack,
  onSessionExpired,
  onOpenPolicies,
}: ApplicationPageProps) {
  const params = new URLSearchParams(window.location.search);
  const productId = params.get('product_id');
  const planId = params.get('plan_id');
  const itemId = params.get('item_id');
  const [currentStep, setCurrentStep] = useState(0);
  const [paying, setPaying] = useState(false);
  const [paid, setPaid] = useState(false);
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function pay() {
    if (!planId || !itemId || paying || paid) return;
    setPaying(true);
    setError(null);
    try {
      // 两步：先下单（待支付），再支付。
      // 拆成两个接口是为了让"订单"成为真实的业务对象——
      // 支付成功才发保单，订单上能查到金额和订单号。
      const created = await createOrder({ planId, itemIds: [itemId] });
      const paidOrder = await payOrder(created.id);
      setOrder(paidOrder);
      setPaid(true);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionExpired();
        return;
      }
      setError(errorText(reason, '支付失败，请稍后重试'));
    } finally {
      setPaying(false);
    }
  }

  const isLastStep = currentStep === APPLICATION_STEPS.length - 1;

  return (
    <div className="application-page">
      <header className="advisor-header">
        <div className="brand">
          <span className="brand__mark"><SafetyCertificateOutlined /></span>
          <span><strong>安心保</strong><small>投保流程</small></span>
        </div>
        <div>
          <span>{session.user.displayName || session.user.username}</span>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回顾问</Button>
        </div>
      </header>

      <main className="application-shell">
        <section className="application-header">
          <div>
            <span>当前产品</span>
            <h1>{productId ? `产品 ID：${productId}` : '未选择产品'}</h1>
            <p>投保以页面流程为主，顾问负责解释条款、填写项和注意事项。</p>
          </div>
        </section>

        <section className="application-flow">
          <Steps current={paid ? APPLICATION_STEPS.length : currentStep} items={APPLICATION_STEPS} />
          {error ? <Alert showIcon type="error" message={error} /> : null}
          {paid ? (
            <Alert
              showIcon
              type="success"
              message="支付成功"
              description={
                order
                  ? `订单号 ${order.order_number ?? '—'}，实付 ¥${moneyOf(order.total_premium)}。保单已生成，可以在「我的保单」里查看。`
                  : '保单已生成，可以在「我的保单」里查看。'
              }
              action={<Button size="small" onClick={onOpenPolicies}>我的保单</Button>}
            />
          ) : (
            <div className="application-step-body">
              <h2>{APPLICATION_STEPS[currentStep].title}</h2>
              <p>演示版本暂不填写本步骤内容，点击按钮直接进入下一步。</p>
              {!planId || !itemId ? (
                <Alert
                  showIcon
                  type="warning"
                  message="缺少方案项信息"
                  description="当前入口没有携带 plan_id 或 item_id，无法在支付后更新投保状态。请从保险方案产品卡片进入投保流程。"
                />
              ) : null}
              <div className="application-actions">
                {currentStep > 0 ? (
                  <Button onClick={() => setCurrentStep((step) => step - 1)}>上一步</Button>
                ) : null}
                {isLastStep ? (
                  <Button
                    type="primary"
                    icon={<PayCircleOutlined />}
                    loading={paying}
                    disabled={!planId || !itemId}
                    onClick={() => void pay()}
                  >
                    支付
                  </Button>
                ) : (
                  <Button type="primary" onClick={() => setCurrentStep((step) => step + 1)}>
                    下一步
                  </Button>
                )}
              </div>
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

/** 金额展示：后端返回的是数字或数字字符串，统一按千分位显示 */
function moneyOf(value: unknown): string {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toLocaleString('zh-CN') : String(value ?? '—');
}
