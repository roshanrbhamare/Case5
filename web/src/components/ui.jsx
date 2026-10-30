import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { Inbox, X } from 'lucide-react';

export function PageHead({ title, sub, actions }) {
  return (
    <div className="page-head">
      <div>
        <h1 className="page-title">{title}</h1>
        {sub && <p className="page-sub">{sub}</p>}
      </div>
      {actions && <div className="row">{actions}</div>}
    </div>
  );
}

export function Card({ title, sub, icon, actions, children, pad = true, className = '' }) {
  return (
    <section className={`card ${className}`}>
      {(title || actions) && (
        <header className="card-head">
          <div>
            <h3 className="card-title">
              {icon}
              {title}
            </h3>
            {sub && <p className="card-sub">{sub}</p>}
          </div>
          {actions}
        </header>
      )}
      <div className={pad ? 'card-pad' : ''}>{children}</div>
    </section>
  );
}

export function StatCard({ label, value, icon, tone = '', foot }) {
  return (
    <div className="card stat">
      <div className="stat-top">
        <span className="stat-label">{label}</span>
        <span className={`stat-icon ${tone}`}>{icon}</span>
      </div>
      <div className="stat-value num">{value}</div>
      {foot && <div className="stat-foot">{foot}</div>}
    </div>
  );
}

const STATUS_STYLE = {
  ACTIVE: ['green', 'Active'],
  INACTIVE: ['grey', 'Inactive'],
  COMPLETED: ['green', 'Completed'],
  BLOCKED: ['amber', 'Needs review'],
  APPROVED: ['blue', 'Approved'],
  APPLYING: ['blue', 'Applying'],
  ANALYZING: ['blue', 'Checking'],
  QUEUED: ['blue', 'Queued'],
  STAGING: ['blue', 'Uploading'],
  REJECTED: ['grey', 'Rejected'],
  FAILED: ['red', 'Failed'],
  APPLIED: ['green', 'Applied'],
  STALE: ['violet', 'Stale, ignored'],
  DUPLICATE: ['grey', 'Duplicate, no-op'],
  BOOKED: ['green', 'Booked'],
  CANCELLED: ['red', 'Cancelled'],
  API: ['blue', 'Live feed'],
  NIGHTLY_FILE: ['violet', 'Nightly file'],
};

export function StatusPill({ status, label }) {
  const [tone, text] = STATUS_STYLE[status] ?? ['grey', status];
  return (
    <span className={`pill pill-${tone}`}>
      <span className="dot" />
      {label ?? text}
    </span>
  );
}

export function Empty({ icon = <Inbox size={22} />, title, children }) {
  return (
    <div className="empty">
      <div className="empty-icon">{icon}</div>
      <h4>{title}</h4>
      <div>{children}</div>
    </div>
  );
}

export function Skeleton({ height = 16, width = '100%', style }) {
  return <div className="skeleton" style={{ height, width, ...style }} />;
}

export function Modal({ open, onClose, children }) {
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <div className="modal" role="dialog" aria-modal="true">
        <button className="btn btn-ghost btn-sm" style={{ position: 'absolute', right: 12, top: 12, padding: '0 8px' }} onClick={onClose} aria-label="Close">
          <X size={18} />
        </button>
        {children}
      </div>
    </>
  );
}

export function Drawer({ onClose, children }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true">
        {children}
      </aside>
    </>
  );
}

const ToastContext = createContext(() => {});

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const push = useCallback((toast) => {
    const id = Math.random().toString(36).slice(2);
    setToasts((t) => [...t, { id, ...toast }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), toast.error ? 7000 : 4000);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={`toast ${t.error ? 'error' : ''}`}>
            <div>{t.message}</div>
            {t.requestId && <div className="ref">Reference: {t.requestId}</div>}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  const push = useContext(ToastContext);
  return {
    success: (message) => push({ message }),
    error: (err) => push({ message: err?.message ?? String(err), requestId: err?.requestId, error: true }),
  };
}
