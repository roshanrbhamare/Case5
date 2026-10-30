import { useState } from 'react';
import { ArrowRight, Clock3, KeyRound, ShieldCheck, Undo2 } from 'lucide-react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { BrandMark } from '../components/Layout.jsx';

// Development-only demo accounts created by the seed script.
const DEMO = [
  { email: 'operator@acme.test', name: 'Meera Iyer', role: 'Sync operator · Acme' },
  { email: 'admin@acme.test', name: 'Rahul Menon', role: 'Transport admin · Acme' },
  { email: 'employee@acme.test', name: 'Ananya Sharma', role: 'Employee · Acme' },
  { email: 'operator@globex.test', name: 'Vikram Singh', role: 'Sync operator · Globex' },
];
const DEMO_PASSWORD = 'Demo@1234';
// An employee the seeded HR feed created; any active employee's email works.
const DEMO_EMPLOYEE_EMAIL = 'aditya.gupta7@acme.example';

function CodeSignIn() {
  const { signInWithCode } = useAuth();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function run(action) {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  function sendCode(e, to = email) {
    e?.preventDefault();
    setEmail(to);
    setCode('');
    run(async () => setSent(await api('/v1/auth/sign-in-code', { method: 'POST', body: { email: to } })));
  }

  function verify(e) {
    e.preventDefault();
    run(() => signInWithCode(email, code));
  }

  if (!sent) {
    return (
      <>
        <form onSubmit={sendCode} className="stack" style={{ gap: 14 }}>
          <div>
            <label className="label" htmlFor="code-email">
              Work email
            </label>
            <input
              id="code-email"
              className="input"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@company.com"
              required
            />
          </div>
          {error && <div className="banner banner-red small">{error}</div>}
          <button className="btn btn-primary btn-block" disabled={busy}>
            {busy ? 'Sending…' : 'Send me a code'} {!busy && <ArrowRight size={16} />}
          </button>
        </form>
        <p className="small muted" style={{ marginTop: 16 }}>
          No password needed: use the work email your company's HR team has for you.{' '}
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            style={{ padding: '0 4px', height: 'auto' }}
            disabled={busy}
            onClick={() => sendCode(null, DEMO_EMPLOYEE_EMAIL)}
          >
            Try a demo employee
          </button>
        </p>
      </>
    );
  }

  return (
    <form onSubmit={verify} className="stack" style={{ gap: 14 }}>
      <p className="small muted">
        If <strong>{email}</strong> belongs to an active employee, a 6-digit code is on its way. It expires in {Math.round(sent.expiresInSeconds / 60)} minutes.
      </p>
      {sent.demoCode && (
        <div className="banner banner-green small">
          <span className="banner-icon" style={{ color: 'var(--green)' }}>
            <KeyRound size={16} />
          </span>
          <div>
            Local demo: no email is sent, so here is your code: <strong className="num">{sent.demoCode}</strong>
          </div>
        </div>
      )}
      <div>
        <label className="label" htmlFor="code">
          6-digit code
        </label>
        <input
          id="code"
          className="input code-input"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
          placeholder="••••••"
          required
          autoFocus
        />
      </div>
      {error && <div className="banner banner-red small">{error}</div>}
      <button className="btn btn-primary btn-block" disabled={busy || code.length !== 6}>
        {busy ? 'Signing in…' : 'Sign in'} {!busy && <ArrowRight size={16} />}
      </button>
      <div className="spread small">
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setSent(null)} disabled={busy}>
          Use a different email
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => sendCode(null)} disabled={busy}>
          Send a new code
        </button>
      </div>
    </form>
  );
}

export function Login() {
  const { login } = useAuth();
  const [mode, setMode] = useState('staff');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e, override) {
    e?.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(override?.email ?? email, override?.password ?? password);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login">
      <section className="login-art">
        <div className="row" style={{ gap: 12 }}>
          <span className="brand-mark" style={{ background: '#fff' }}>
            <svg width="20" height="20" viewBox="0 0 32 32" aria-hidden="true">
              <path d="M7 23c4-10 14-3 18-14" stroke="#43B02A" strokeWidth="3.2" fill="none" strokeLinecap="round" />
              <circle cx="7" cy="23" r="3" fill="#43B02A" />
              <circle cx="25" cy="9" r="3" fill="#43B02A" />
            </svg>
          </span>
          <div style={{ fontWeight: 800, fontSize: 18 }}>RosterSync</div>
        </div>

        <svg className="login-route" width="520" height="520" viewBox="0 0 520 520" aria-hidden="true">
          <path
            d="M40 470 C 160 300, 260 420, 330 260 S 470 120, 490 40"
            stroke="#fff"
            strokeWidth="14"
            fill="none"
            strokeLinecap="round"
            strokeDasharray="2 30"
          />
          <circle cx="40" cy="470" r="18" fill="#fff" />
          <circle cx="490" cy="40" r="18" fill="#fff" />
        </svg>

        <h1>Every employee on the right cab, even when the HR feed isn't perfect.</h1>
        <p>RosterSync keeps your employee records correct while HR changes arrive twice, out of order, half-finished or wrong.</p>

        <div className="login-points">
          <div className="login-point">
            <Undo2 size={18} />
            <div>Replays and late updates are recognised by version and safely ignored.</div>
          </div>
          <div className="login-point">
            <ShieldCheck size={18} />
            <div>A broken nightly export is stopped before it can switch anyone's cab off.</div>
          </div>
          <div className="login-point">
            <Clock3 size={18} />
            <div>Exits cancel future rides and free seats automatically, exactly once.</div>
          </div>
        </div>
      </section>

      <section className="login-form">
        <div className="login-box">
          <div className="row" style={{ marginBottom: 20 }}>
            <BrandMark size={38} />
          </div>
          <h2 style={{ fontSize: 24, fontWeight: 700, letterSpacing: '-0.02em' }}>{mode === 'staff' ? 'Welcome back' : 'Your commute'}</h2>
          <p className="muted" style={{ marginTop: 4, marginBottom: 20 }}>
            {mode === 'staff' ? "Sign in to manage your company's commute roster." : 'See your upcoming cabs. We will send you a one-time code.'}
          </p>

          <div className="login-modes" role="tablist">
            <button type="button" role="tab" aria-selected={mode === 'staff'} className={mode === 'staff' ? 'on' : ''} onClick={() => setMode('staff')}>
              Staff
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={mode === 'employee'}
              className={mode === 'employee' ? 'on' : ''}
              onClick={() => setMode('employee')}
            >
              Employee
            </button>
          </div>

          {mode === 'employee' ? (
            <CodeSignIn />
          ) : (
            <>
              <form onSubmit={submit} className="stack" style={{ gap: 14 }}>
                <div>
                  <label className="label" htmlFor="email">
                    Work email
                  </label>
                  <input
                    id="email"
                    className="input"
                    type="email"
                    autoComplete="username"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="you@company.com"
                    required
                  />
                </div>
                <div>
                  <label className="label" htmlFor="password">
                    Password
                  </label>
                  <input
                    id="password"
                    className="input"
                    type="password"
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    required
                  />
                </div>
                {error && <div className="banner banner-red small">{error}</div>}
                <button className="btn btn-primary btn-block" disabled={busy}>
                  {busy ? 'Signing in…' : 'Sign in'} {!busy && <ArrowRight size={16} />}
                </button>
              </form>

              <div style={{ marginTop: 28 }}>
                <div className="spread">
                  <span className="label" style={{ margin: 0 }}>
                    Demo accounts
                  </span>
                  <span className="small muted">Local demo data only</span>
                </div>
                <div className="demo-accounts">
                  {DEMO.map((d) => (
                    <button
                      key={d.email}
                      type="button"
                      className="demo-account"
                      disabled={busy}
                      onClick={() => submit(null, { email: d.email, password: DEMO_PASSWORD })}
                    >
                      <span className="avatar">
                        {d.name
                          .split(' ')
                          .map((p) => p[0])
                          .join('')}
                      </span>
                      <span className="who">
                        <span className="who-name" style={{ display: 'block' }}>
                          {d.name}
                        </span>
                        <span className="who-meta">{d.role}</span>
                      </span>
                      <ArrowRight size={16} className="muted" style={{ marginLeft: 'auto' }} />
                    </button>
                  ))}
                </div>
              </div>
            </>
          )}
        </div>
      </section>
    </div>
  );
}
