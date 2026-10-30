import { NavLink, Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Activity, CarFront, FileSpreadsheet, FlaskConical, LayoutDashboard, LogOut, Users } from 'lucide-react';
import { api } from '../api.js';
import { ROLE_LABEL, useAuth } from '../auth.jsx';
import { initials } from '../lib/format.js';

export function BrandMark({ size = 34 }) {
  return (
    <span className="brand-mark" style={{ width: size, height: size }}>
      <svg width={size * 0.6} height={size * 0.6} viewBox="0 0 32 32" aria-hidden="true">
        <path d="M7 23c4-10 14-3 18-14" stroke="#fff" strokeWidth="3.2" fill="none" strokeLinecap="round" />
        <circle cx="7" cy="23" r="3" fill="#fff" />
        <circle cx="25" cy="9" r="3" fill="#fff" />
      </svg>
    </span>
  );
}

export function Layout() {
  const { user, logout } = useAuth();
  const isEmployee = user.role === 'EMPLOYEE';
  const summary = useQuery({
    queryKey: ['ops-summary'],
    queryFn: () => api('/v1/ops/summary'),
    enabled: !isEmployee,
    refetchInterval: 15_000,
  });
  const blocked = summary.data?.blockedRun;

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">
          <BrandMark />
          <div>
            <div className="brand-name">RosterSync</div>
            <div className="brand-sub">Employee roster for office commute</div>
          </div>
        </div>

        <nav className="nav">
          {isEmployee ? (
            <NavLink to="/me">
              <CarFront size={18} /> My commute
            </NavLink>
          ) : (
            <>
              <div className="nav-label">Overview</div>
              <NavLink to="/" end>
                <LayoutDashboard size={18} /> Dashboard
              </NavLink>
              <NavLink to="/employees">
                <Users size={18} /> Employees
              </NavLink>
              <div className="nav-label">HR sync</div>
              <NavLink to="/nightly">
                <FileSpreadsheet size={18} /> Nightly file
                {blocked && <span className="nav-badge">1</span>}
              </NavLink>
              <NavLink to="/activity">
                <Activity size={18} /> Sync activity
              </NavLink>
              {user.role === 'OPERATOR' && (
                <NavLink to="/test-feed">
                  <FlaskConical size={18} /> Test feed
                </NavLink>
              )}
            </>
          )}
        </nav>

        <div className="sidebar-foot">
          <div className="tenant-card">
            <span className="avatar">{initials(user.displayName)}</span>
            <div className="who">
              <div className="who-name">{user.displayName}</div>
              <div className="who-meta">
                {ROLE_LABEL[user.role]} · {user.tenantName}
              </div>
            </div>
            <button className="btn btn-ghost btn-sm" onClick={logout} title="Sign out" aria-label="Sign out" style={{ marginLeft: 'auto', padding: '0 8px' }}>
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>

      <main className="main">
        <Outlet />
      </main>
    </div>
  );
}
