import { ArrowLeftOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Alert, Button, Empty, Input, InputNumber, Select, Skeleton, Spin } from 'antd';
import { useEffect, useState } from 'react';

import { ApiError, createClaim, errorText, getClaimMaterials, listClaims, listPolicies } from './api';
import { dateText, moneyOf } from './insuranceCards';
import type { AuthSession, Claim, ClaimMaterials, Policy } from './types';

interface ClaimsPageProps {
  session: AuthSession;
  onBack: () => void;
  onSessionExpired: () => void;
  onOpenPolicies: () => void;
}

const CLAIM_TYPE_LABELS: Record<string, string> = {
  medical: '医疗',
  accident: '意外',
  critical_illness: '重疾',
  life: '身故',
};

const CLAIM_TYPE_OPTIONS = Object.entries(CLAIM_TYPE_LABELS).map(([value, label]) => ({
  value,
  label,
}));

const CLAIM_STATUS_LABELS: Record<string, string> = {
  submitted: '已提交',
  reviewing: '审核中',
  approved: '已通过',
  rejected: '已拒赔',
  paid: '已赔付',
};

/**
 * 按险种给出的常见材料清单。
 * 这里没有做附件上传，只是把"要准备什么"讲清楚 —— 材料口径以条款和保司要求为准。
 */
const CLAIM_MATERIALS: Record<string, string[]> = {
  medical: ['住院病历、出院小结', '医疗费用发票原件', '费用明细清单', '身份证件、本人银行卡'],
  critical_illness: ['二级以上医院诊断证明', '病理报告或影像检查报告', '住院病历', '身份证件、本人银行卡'],
  accident: ['事故证明（如交通事故认定书）', '门急诊病历与检查报告', '医疗费用票据', '身份证件、本人银行卡'],
  life: ['死亡证明或户籍注销证明', '受益人与被保险人关系证明', '身份证件、本人银行卡'],
};

/** 状态 → 徽标颜色，复用保单卡的样式类 */
const CLAIM_STATUS_CLASS: Record<string, string> = {
  submitted: 'pending',
  reviewing: 'pending',
  approved: 'active',
  paid: 'active',
  rejected: 'expired',
};

/**
 * 我的理赔。
 * 带 policy_id 进来时先显示报案表单（从「我的保单」点「申请理赔」过来的），
 * 没带就直接看历史记录。
 */
export function ClaimsPage({ session, onBack, onSessionExpired, onOpenPolicies }: ClaimsPageProps) {
  const params = new URLSearchParams(window.location.search);
  const policyId = params.get('policy_id');

  const [claims, setClaims] = useState<Claim[]>([]);
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  // 报案表单
  const [claimType, setClaimType] = useState('medical');
  const [amount, setAmount] = useState<number | null>(null);
  const [description, setDescription] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitted, setSubmitted] = useState<Claim | null>(null);

  const policy = policyId ? policies.find((item) => item.id === policyId) ?? null : null;

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    Promise.all([listClaims(), listPolicies()])
      .then(([claimList, policyList]) => {
        if (!alive) return;
        setClaims(claimList);
        setPolicies(policyList);
      })
      .catch((reason) => {
        if (!alive) return;
        if (reason instanceof ApiError && reason.status === 401) {
          onSessionExpired();
          return;
        }
        setError(errorText(reason, '理赔数据加载失败'));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [reloadKey]);

  // 保单险种能对上理赔类型时，默认选中它（医疗险默认"医疗"）
  useEffect(() => {
    if (policy?.category && CLAIM_TYPE_LABELS[policy.category]) {
      setClaimType(policy.category);
    }
  }, [policy?.id]);

  // 材料清单由 Agent 依据条款生成；失败时前端用静态兜底清单
  const [materials, setMaterials] = useState<ClaimMaterials | null>(null);
  const [materialsLoading, setMaterialsLoading] = useState(false);
  useEffect(() => {
    if (!policyId) return;
    let alive = true;
    setMaterialsLoading(true);
    getClaimMaterials(policyId)
      .then((result) => {
        if (alive) setMaterials(result);
      })
      .catch(() => {
        // 生成失败不影响报案，静默回落到静态清单
      })
      .finally(() => {
        if (alive) setMaterialsLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [policyId]);

  async function submit() {
    if (!policy || submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const created = await createClaim({
        policyId: policy.id,
        claimType,
        amount: amount === null ? undefined : amount,
        description: description.trim() || undefined,
      });
      setSubmitted(created);
      setClaims((current) => [created, ...current]);
      setAmount(null);
      setDescription('');
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) {
        onSessionExpired();
        return;
      }
      setSubmitError(errorText(reason, '提交失败，请稍后重试'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="application-page">
      <header className="advisor-header">
        <div className="brand">
          <span className="brand__mark"><SafetyCertificateOutlined /></span>
          <span><strong>安心保</strong><small>我的理赔</small></span>
        </div>
        <div>
          <span>{session.user.displayName || session.user.username}</span>
          <Button onClick={onOpenPolicies}>我的保单</Button>
          <Button icon={<ArrowLeftOutlined />} onClick={onBack}>返回商城</Button>
        </div>
      </header>

      <main className="application-shell">
        <section className="application-header">
          <span>理赔服务</span>
          <h1>{loading ? '正在加载你的理赔记录' : `共 ${claims.length} 条理赔记录`}</h1>
          <p>受理后由保险公司审核赔付，进度以理赔单号为准；有疑问可以在智能顾问里问理赔流程和材料。</p>
        </section>

        {error ? (
          <Alert
            showIcon
            type="error"
            message="理赔数据加载失败"
            description={error}
            action={<Button onClick={() => setReloadKey((value) => value + 1)}>重新加载</Button>}
          />
        ) : null}

        {loading ? <Skeleton active paragraph={{ rows: 4 }} /> : null}

        {/* 报案表单：只有从保单卡片点「申请理赔」带 policy_id 进来才显示 */}
        {!loading && policyId ? (
          policy ? (
            <section className="application-flow" style={{ marginBottom: 16 }}>
              <h2 style={{ margin: 0 }}>申请理赔</h2>
              <div className="quote-result">
                <div>
                  <strong>{policy.product_name || '保险产品'}</strong>
                  <span className="quote-result__note">　保单号 {policy.policy_number || '—'}</span>
                </div>
                <span className="quote-result__note">
                  保障期间 {dateText(policy.effective_at)} 至 {dateText(policy.expires_at)}
                </span>
              </div>

              {submitted ? (
                <Alert
                  showIcon
                  type="success"
                  message={`理赔已受理，单号 ${submitted.claim_number ?? '—'}`}
                  description="请留意保险公司来电或短信通知；可以在下方列表里查看状态。"
                />
              ) : (
                <>
                  {/* 材料清单：优先用 Agent 依据条款生成的结果，失败时回落到静态清单 */}
                  {materialsLoading ? (
                    <div className="claim-materials">
                      <span><Spin size="small" />　正在依据条款生成材料清单…</span>
                    </div>
                  ) : (
                    <ClaimMaterialsBlock policy={policy} materials={materials} />
                  )}

                  <div className="quote-form">
                    <div className="quote-field">
                      <label htmlFor="claim-type">理赔类型</label>
                      <Select
                        id="claim-type"
                        value={claimType}
                        options={CLAIM_TYPE_OPTIONS}
                        onChange={(value) => setClaimType(value)}
                      />
                    </div>
                    <div className="quote-field">
                      <label htmlFor="claim-amount">
                        申请金额<span className="quote-unit">元，可留空</span>
                      </label>
                      <InputNumber
                        id="claim-amount"
                        value={amount}
                        min={0}
                        step={100}
                        style={{ width: '100%' }}
                        onChange={(value) => setAmount(value ?? null)}
                      />
                    </div>
                  </div>
                  <div className="quote-field">
                    <label htmlFor="claim-desc">
                      事故经过<span className="quote-unit">就诊医院、时间、经过</span>
                    </label>
                    <Input.TextArea
                      id="claim-desc"
                      rows={3}
                      maxLength={1000}
                      showCount
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      placeholder="例如：2026-11-20 因急性阑尾炎在 XX 医院住院 5 天，已出院"
                    />
                  </div>

                  {submitError ? <Alert showIcon type="error" message={submitError} /> : null}

                  <div className="application-actions">
                    <Button type="primary" loading={submitting} onClick={() => void submit()}>
                      提交理赔申请
                    </Button>
                  </div>
                </>
              )}
            </section>
          ) : (
            <Alert showIcon type="warning" message="找不到这张保单" description="请回到「我的保单」重新选择。" />
          )
        ) : null}

        {!loading && !error && claims.length === 0 ? (
          <Empty description="还没有理赔记录。在「我的保单」里选一张保障中的保单，点「申请理赔」。" />
        ) : null}

        <div className="policy-card-grid">
          {claims.map((claim) => (
            <ClaimCard claim={claim} key={claim.id} />
          ))}
        </div>
      </main>
    </div>
  );
}

/**
 * 材料清单展示。
 * Agent 生成成功时用它的结果（note 里写明依据哪段条款）；
 * 生成失败或返回为空时，回落到按险种写死的静态清单 —— 不能因为模型不可用就让人没法报案。
 */
function ClaimMaterialsBlock({
  policy,
  materials,
}: {
  policy: Policy;
  materials: ClaimMaterials | null;
}) {
  const generated = materials?.items ?? [];
  const fallback = CLAIM_MATERIALS[String(policy.category ?? '')] ?? [];
  const items = generated.length ? generated : fallback;
  if (items.length === 0) return null;

  return (
    <div className="claim-materials">
      <strong>{generated.length ? '根据条款整理的材料清单' : '一般需要准备的材料'}</strong>
      <ul>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
      <span className="quote-result__note">
        {generated.length
          ? `${materials?.note || '依据条款整理'}；最终以保险公司核赔要求为准。`
          : '最终以保险公司核赔要求为准；材料不全会影响审核时效。'}
      </span>
    </div>
  );
}

function ClaimCard({ claim }: { claim: Claim }) {
  const status = String(claim.status ?? 'submitted');
  return (
    <article className="policy-card">
      <div className="policy-card__top">
        <div>
          <strong>{claim.product_name || '保险产品'}</strong>
          <span>{claim.claim_number || '理赔单号待生成'}</span>
        </div>
        <em className={`policy-status policy-status--${CLAIM_STATUS_CLASS[status] ?? 'pending'}`}>
          {CLAIM_STATUS_LABELS[status] ?? status}
        </em>
      </div>
      <dl>
        <div>
          <dt>理赔类型</dt>
          <dd>{CLAIM_TYPE_LABELS[String(claim.claim_type)] ?? claim.claim_type ?? '—'}</dd>
        </div>
        <div>
          <dt>保单号</dt>
          <dd>{claim.policy_number || '—'}</dd>
        </div>
        <div>
          <dt>申请金额</dt>
          <dd>{claim.amount === null || claim.amount === undefined ? '未填写' : `¥${moneyOf(claim.amount)}`}</dd>
        </div>
        <div>
          <dt>提交时间</dt>
          <dd>{dateText(claim.submitted_at)}</dd>
        </div>
        {claim.description ? (
          <div style={{ gridColumn: '1 / -1' }}>
            <dt>事故经过</dt>
            <dd>{claim.description}</dd>
          </div>
        ) : null}
      </dl>
    </article>
  );
}
