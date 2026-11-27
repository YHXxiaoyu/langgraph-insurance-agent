import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  FileTextOutlined,
  PayCircleOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { Alert, Button, Checkbox, Input, InputNumber, Select, Spin, Steps } from 'antd';
import { useEffect, useState } from 'react';

import {
  ApiError,
  createOrder,
  createPremiumQuote,
  errorText,
  getPricingForm,
  payOrder,
} from './api';
import { formatCategoryLabel } from './categoryLabels';
import type { AuthSession, Order, PremiumQuote, PricingField, PricingForm } from './types';

interface ApplicationPageProps {
  session: AuthSession;
  onBack: () => void;
  onSessionExpired: () => void;
  onOpenPolicies: () => void;
}

/**
 * 三步：试算 → 确认 → 完成。
 * 原来的"客户告知 / 健康告知 / 保单信息"三步是空表单，点了什么都发生不了；
 * 合并成一步之后，这一步至少有三样真东西：试算出来的价、被保人参数、两项声明。
 */
const APPLICATION_STEPS = [
  { title: '保费试算', icon: <PayCircleOutlined /> },
  { title: '确认投保', icon: <FileTextOutlined /> },
  { title: '支付完成', icon: <CheckCircleOutlined /> },
];

const GENDER_LABELS: Record<string, string> = {
  male: '男',
  female: '女',
  other: '其他',
};

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

  // 确认页的两项声明：真实投保里它们是必填的，这里也做成"不勾就不能付"
  const [agreedClause, setAgreedClause] = useState(false);
  const [agreedHealth, setAgreedHealth] = useState(false);

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
      // 换了价格，之前的勾选要重来
      setAgreedClause(false);
      setAgreedHealth(false);
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
    if (!planId || !itemId || paying || paid || !quote) return;
    setPaying(true);
    setError(null);
    try {
      // 下单时带上试算 id：订单金额按试算价算，而不是方案项里的预算估计
      const created = await createOrder({
        planId,
        itemIds: [itemId],
        quoteId: quote.id,
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
    // 改了参数，之前算出来的价就不作数了，勾选也要重来
    setQuote(null);
    setAgreedClause(false);
    setAgreedHealth(false);
  }

  const canConfirm = agreedClause && agreedHealth && !paying;
  const stepIndex = paid ? APPLICATION_STEPS.length : currentStep;

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
          <Steps current={stepIndex} items={APPLICATION_STEPS} />

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
          ) : null}

          {/* 第一步：保费试算 */}
          {!paid && currentStep === 0 ? (
            <div className="application-step-body">
              <h2>保费试算</h2>
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

              {!planId || !itemId ? (
                <Alert
                  showIcon
                  type="warning"
                  message="缺少方案项信息"
                  description="当前入口没有携带 plan_id 或 item_id，无法在支付后更新投保状态。请从保险方案产品卡片进入投保流程。"
                />
              ) : null}

              <div className="application-actions">
                <Button
                  type="primary"
                  disabled={!quote}
                  onClick={() => setCurrentStep(1)}
                >
                  下一步
                </Button>
              </div>
            </div>
          ) : null}

          {/* 第二步：确认投保（原来的三步空表单合并到这里） */}
          {!paid && currentStep === 1 && quote ? (
            <div className="application-step-body">
              <h2>确认投保信息</h2>
              <p>请核对信息并阅读条款。确认后即进入支付，保单在支付成功时生成。</p>

              <div className="quote-result">
                <div className="quote-result__total">
                  <span>应付保费</span>
                  <strong>¥{moneyOf(quote.total_premium)}</strong>
                  <span>/ 年</span>
                </div>
                <dl className="quote-confirm-grid">
                  <div>
                    <dt>产品</dt>
                    <dd>{quote.product_name || form?.product_name || '—'}</dd>
                  </div>
                  <div>
                    <dt>险种</dt>
                    <dd>{formatCategoryLabel(quote.category)}</dd>
                  </div>
                  <div>
                    <dt>被保人</dt>
                    <dd>
                      {textOf(paramOf(quote, 'age'))} 岁 · {GENDER_LABELS[textOf(paramOf(quote, 'gender'))] ?? '—'}
                    </dd>
                  </div>
                  <div>
                    <dt>保额</dt>
                    <dd>¥{moneyOf(paramOf(quote, 'coverage_amount'))}</dd>
                  </div>
                  {hasParam(quote, 'social_security') ? (
                    <div>
                      <dt>医保</dt>
                      <dd>{textOf(paramOf(quote, 'social_security')) === 'no' ? '无' : '有'}</dd>
                    </div>
                  ) : null}
                  <div>
                    <dt>保费有效期</dt>
                    <dd>{dateTimeOf(quote.expires_at)}</dd>
                  </div>
                </dl>
                <ul>
                  {(quote.breakdown?.explain ?? []).map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </ul>
              </div>

              <div className="quote-declarations">
                <Checkbox
                  checked={agreedClause}
                  onChange={(event) => setAgreedClause(event.target.checked)}
                >
                  我已阅读并理解保险条款，包括保险责任、免除责任和等待期
                </Checkbox>
                <Checkbox
                  checked={agreedHealth}
                  onChange={(event) => setAgreedHealth(event.target.checked)}
                >
                  被保人身体健康，符合投保条件，所填信息真实有效
                </Checkbox>
              </div>

              <div className="application-actions">
                <Button onClick={() => setCurrentStep(0)}>上一步</Button>
                <Button
                  type="primary"
                  icon={<PayCircleOutlined />}
                  loading={paying}
                  disabled={!canConfirm || !planId || !itemId}
                  onClick={() => void pay()}
                >
                  确认并支付
                </Button>
              </div>

              {!planId || !itemId ? (
                <Alert
                  showIcon
                  type="warning"
                  message="缺少方案项信息"
                  description="当前入口没有携带 plan_id 或 item_id，无法在支付后更新投保状态。请从保险方案产品卡片进入投保流程。"
                />
              ) : null}
            </div>
          ) : null}
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

/** 试算入参快照里的某个字段；后端存的是 snake_case */
function paramOf(quote: PremiumQuote, key: string): unknown {
  return quote.params?.[key];
}

function hasParam(quote: PremiumQuote, key: string): boolean {
  return quote.params ? key in quote.params : false;
}

/** 参数展示：null/undefined 统一显示成 — */
function textOf(value: unknown): string {
  if (value === null || value === undefined) return '—';
  return String(value);
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
