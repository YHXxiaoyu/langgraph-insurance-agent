import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  FileTextOutlined,
  HeartOutlined,
  IdcardOutlined,
  PayCircleOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Alert, Button, Input, InputNumber, Select, Spin, Steps } from 'antd';
import { useEffect, useState } from 'react';

import {
  ApiError,
  createOrder,
  createPremiumQuote,
  errorText,
  getPricingForm,
  payOrder,
} from './api';
import type { AuthSession, Order, PremiumQuote, PricingField, PricingForm } from './types';

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

  // 试算相关：表单定义来自后端，值放一个对象里
  const [form, setForm] = useState<PricingForm | null>(null);
  const [formLoading, setFormLoading] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [values, setValues] = useState<Record<string, unknown>>({});
  const [quoting, setQuoting] = useState(false);
  const [quote, setQuote] = useState<PremiumQuote | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);

  // 进页面先拿表单定义：字段、默认值、选项都由后端给，前端不写死
  useEffect(() => {
    if (!productId) return;
    let alive = true;
    setFormLoading(true);
    setFormError(null);
    getPricingForm(productId)
      .then((result) => {
        if (!alive) return;
        setForm(result);
        const initial: Record<string, unknown> = {};
        (result.fields ?? []).forEach((field) => {
          initial[field.key] = field.default;
        });
        setValues(initial);
      })
      .catch((reason) => {
        if (!alive) return;
        if (reason instanceof ApiError && reason.status === 401) {
          onSessionExpired();
          return;
        }
        setFormError(errorText(reason, '试算表单加载失败'));
      })
      .finally(() => {
        if (alive) setFormLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [productId]);

  async function calculate() {
    if (!productId || quoting) return;
    const age = Number(values.age);
    const coverage = Number(values.coverage_amount);
    if (!Number.isFinite(age) || !Number.isFinite(coverage)) {
      setQuoteError('请填写年龄和保额');
      return;
    }
    setQuoting(true);
    setQuoteError(null);
    try {
      const result = await createPremiumQuote({
        productId,
        age,
        gender: String(values.gender ?? 'male'),
        coverageAmount: coverage,
        socialSecurity: values.social_security ? String(values.social_security) : undefined,
        occupationClass: values.occupation_class ? String(values.occupation_class) : undefined,
      });
      setQuote(result);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionExpired();
        return;
      }
      setQuoteError(errorText(reason, '试算失败，请稍后重试'));
    } finally {
      setQuoting(false);
    }
  }

  async function pay() {
    if (!planId || !itemId || paying || paid) return;
    setPaying(true);
    setError(null);
    try {
      // 下单时带上试算 id：订单金额按试算价算，而不是方案项里的预算估计
      const created = await createOrder({
        planId,
        itemIds: [itemId],
        quoteId: quote?.id,
      });
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

  function setValue(key: string, value: unknown) {
    setValues((current) => ({ ...current, [key]: value }));
    // 改了参数，之前算出来的价就不作数了
    setQuote(null);
  }

  const isLastStep = currentStep === APPLICATION_STEPS.length - 1;
  // 第一步必须先试算出来一个价，后面才好"确认支付"
  const canGoNext = currentStep !== 0 || quote !== null;

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
            <h1>{form?.product_name || (productId ? `产品 ID：${productId}` : '未选择产品')}</h1>
            <p>{form?.note || '投保以页面流程为主，顾问负责解释条款、填写项和注意事项。'}</p>
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

              {currentStep === 0 ? (
                <QuoteStep
                  form={form}
                  loading={formLoading}
                  error={formError}
                  values={values}
                  quote={quote}
                  quoteError={quoteError}
                  quoting={quoting}
                  onChange={setValue}
                  onCalculate={() => void calculate()}
                />
              ) : null}

              {currentStep === 4 ? (
                <div className="quote-result">
                  <div className="quote-result__total">
                    <span>应付保费</span>
                    <strong>¥{moneyOf(quote?.total_premium)}</strong>
                    <span>/ 年</span>
                  </div>
                  <span className="quote-result__note">
                    本次价格来自 {quote?.id ? '已生成的试算记录' : '方案预算'}
                    {quote?.expires_at ? `，有效期至 ${dateTimeOf(quote.expires_at)}` : ''}。
                  </span>
                </div>
              ) : null}

              {currentStep >= 1 && currentStep <= 3 ? (
                <p>演示版本暂不填写本步骤内容，点击按钮直接进入下一步。</p>
              ) : null}

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
                    disabled={!planId || !itemId || !quote}
                    onClick={() => void pay()}
                  >
                    支付
                  </Button>
                ) : (
                  <Button
                    type="primary"
                    disabled={!canGoNext}
                    onClick={() => setCurrentStep((step) => step + 1)}
                  >
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

/**
 * 第一步：保费试算。
 * 表单字段完全由后端返回的 fields 驱动 —— 以后产品加一个"职业类别"，
 * 后端改数据就行，这个组件一行不用动。
 */
function QuoteStep({
  form,
  loading,
  error,
  values,
  quote,
  quoteError,
  quoting,
  onChange,
  onCalculate,
}: {
  form: PricingForm | null;
  loading: boolean;
  error: string | null;
  values: Record<string, unknown>;
  quote: PremiumQuote | null;
  quoteError: string | null;
  quoting: boolean;
  onChange: (key: string, value: unknown) => void;
  onCalculate: () => void;
}) {
  if (loading) return <Spin />;
  if (error) return <Alert showIcon type="error" message={error} />;
  if (!form) return null;

  return (
    <>
      <p>填写被保人信息，按产品当前费率计算年保费。同一组参数永远算出同一个价。</p>

      <div className="quote-form">
        {(form.fields ?? []).map((field) => (
          <div className="quote-field" key={field.key}>
            <label htmlFor={`quote-${field.key}`}>
              {field.label}
              {field.unit ? <span className="quote-unit">{field.unit}</span> : null}
            </label>
            <FieldInput
              id={`quote-${field.key}`}
              field={field}
              value={values[field.key]}
              onChange={(value) => onChange(field.key, value)}
            />
          </div>
        ))}
      </div>

      {quoteError ? <Alert showIcon type="error" message={quoteError} /> : null}

      <div className="application-actions">
        <Button type="primary" ghost loading={quoting} onClick={onCalculate}>
          试算保费
        </Button>
      </div>

      {quote ? (
        <div className="quote-result">
          <div className="quote-result__total">
            <span>年保费</span>
            <strong>¥{moneyOf(quote.total_premium)}</strong>
            <span>/ 年</span>
          </div>
          <ul>
            {(quote.breakdown?.explain ?? []).map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
          <span className="quote-result__note">
            报价有效期至 {dateTimeOf(quote.expires_at)}，过后需重新试算。
          </span>
        </div>
      ) : null}
    </>
  );
}

/** 按字段的 type 渲染对应控件 */
function FieldInput({
  id,
  field,
  value,
  onChange,
}: {
  id: string;
  field: PricingField;
  value: unknown;
  onChange: (value: unknown) => void;
}) {
  if (field.type === 'select') {
    return (
      <Select
        id={id}
        value={value as string | undefined}
        options={field.options}
        onChange={(next) => onChange(next)}
      />
    );
  }
  if (field.type === 'number') {
    return (
      <InputNumber
        id={id}
        value={value as number | undefined}
        min={field.min}
        max={field.max}
        step={field.step ?? 1}
        style={{ width: '100%' }}
        onChange={(next) => onChange(next)}
      />
    );
  }
  return (
    <Input
      id={id}
      value={(value as string | undefined) ?? ''}
      placeholder={field.placeholder}
      onChange={(event) => onChange(event.target.value)}
    />
  );
}

/** 金额展示：后端返回的是数字或数字字符串，统一按千分位显示 */
function moneyOf(value: unknown): string {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toLocaleString('zh-CN') : String(value ?? '—');
}

/** ISO 时间串 → 2026-11-28 14:27 */
function dateTimeOf(value: unknown): string {
  const text = value === null || value === undefined ? '' : String(value);
  return text ? text.replace('T', ' ').slice(0, 16) : '—';
}
