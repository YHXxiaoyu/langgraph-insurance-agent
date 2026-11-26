import { useEffect, useState } from 'react';

import { AdvisorPage } from './AdvisorPage';
import { ApplicationPage } from './ApplicationPage';
import { clearSession, readSession, saveSession } from './auth';
import { HomePage } from './HomePage';
import { OrdersPage } from './OrdersPage';
import { PoliciesPage } from './PoliciesPage';
import type { AuthSession } from './types';

function navigate(path: string) {
  window.history.pushState({}, '', path);
  window.dispatchEvent(new PopStateEvent('popstate'));
}

function applyPath(target: { productId: string; planId: string; itemId: string }) {
  const params = new URLSearchParams({
    product_id: target.productId,
    plan_id: target.planId,
    item_id: target.itemId,
  });
  return `/apply?${params.toString()}`;
}

export default function App() {
  const [path, setPath] = useState(window.location.pathname);
  const [session, setSession] = useState<AuthSession | null>(() => readSession());

  useEffect(() => {
    const updatePath = () => setPath(window.location.pathname);
    window.addEventListener('popstate', updatePath);
    return () => window.removeEventListener('popstate', updatePath);
  }, []);

  function authenticated(nextSession: AuthSession) {
    saveSession(nextSession);
    setSession(nextSession);
  }

  function logout() {
    clearSession();
    setSession(null);
    if (path === '/advisor' || path === '/apply' || path === '/policies' || path === '/orders') {
      navigate('/');
    }
  }

  function sessionExpired() {
    clearSession();
    setSession(null);
    navigate('/');
  }

  if (path === '/advisor' && session) {
    return (
      <AdvisorPage
        session={session}
        onBack={() => navigate('/')}
        onSessionExpired={sessionExpired}
        onApplyProduct={(target) => navigate(applyPath(target))}
        onOpenPolicies={() => navigate('/policies')}
      />
    );
  }

  if (path === '/apply' && session) {
    return (
      <ApplicationPage
        session={session}
        onBack={() => navigate('/advisor')}
        onSessionExpired={sessionExpired}
        onOpenPolicies={() => navigate('/policies')}
      />
    );
  }

  if (path === '/policies' && session) {
    return (
      <PoliciesPage
        session={session}
        onBack={() => navigate('/')}
        onSessionExpired={sessionExpired}
        onOpenOrders={() => navigate('/orders')}
      />
    );
  }

  if (path === '/orders' && session) {
    return (
      <OrdersPage
        session={session}
        onBack={() => navigate('/')}
        onSessionExpired={sessionExpired}
        onOpenPolicies={() => navigate('/policies')}
      />
    );
  }

  return (
    <HomePage
      session={session}
      initialAuthOpen={
        path === '/advisor' || path === '/apply' || path === '/policies' || path === '/orders'
      }
      onAuthenticated={authenticated}
      onLogout={logout}
      onConsult={() => navigate('/advisor')}
      onOpenPolicies={() => navigate('/policies')}
      onOpenOrders={() => navigate('/orders')}
    />
  );
}
