import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { Alert, Form, Input, Modal } from 'antd';
import { useState } from 'react';

import { ApiError, login } from './api';
import type { AuthSession } from './types';

interface AuthModalProps {
  open: boolean;
  onCancel: () => void;
  onSuccess: (session: AuthSession) => void;
}

interface AuthFormValues {
  username: string;
  password: string;
}

export function AuthModal({
  open,
  onCancel,
  onSuccess,
}: AuthModalProps) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(values: AuthFormValues) {
    setSubmitting(true);
    setError(null);
    try {
      const result = await login(values.username.trim(), values.password);
      // Java 的 UserLoginVO 只有 token / user_id / username，
      // 这里拼成前端统一的 AuthSession 结构
      onSuccess({
        token: result.token,
        user: {
          id: result.user_id,
          username: result.username,
          email: '',
          displayName: null,
        },
      });
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '登录失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  }

  function close() {
    setError(null);
    onCancel();
  }

  return (
    <Modal
      open={open}
      title={null}
      footer={null}
      width={420}
      onCancel={close}
      destroyOnHidden
      centered
    >
      <div className="auth-modal__header">
        <h2>欢迎来到安心保</h2>
        <p>登录后可使用智能顾问服务</p>
      </div>
      <Form<AuthFormValues>
        layout="vertical"
        initialValues={{ username: 'tester7308', password: '123456' }}
        onFinish={submit}
        requiredMark={false}
      >
        <Form.Item
          label="用户名"
          name="username"
          rules={[{ required: true, message: '请输入用户名' }]}
        >
          <Input prefix={<UserOutlined />} size="large" placeholder="请输入用户名" />
        </Form.Item>
        <Form.Item
          label="密码"
          name="password"
          rules={[{ required: true, min: 6, message: '密码至少需要 6 位' }]}
        >
          <Input.Password prefix={<LockOutlined />} size="large" placeholder="请输入密码" />
        </Form.Item>
        {error ? (
          <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />
        ) : null}
        <button className="auth-submit" type="submit" disabled={submitting}>
          {submitting ? '登录中…' : '登录'}
        </button>
      </Form>
    </Modal>
  );
}
