import { useEffect, useState } from 'react';
import { Check, Loader2, RotateCw } from 'lucide-react';
import { serverStatus } from '../api.js';
import { BrandMark } from './Layout.jsx';

const SHOW_AFTER_MS = 700; // a backend that answers this fast never sees the screen
const RETRY_MS = 2500;
const GIVE_UP_MS = 4 * 60_000;
const TYPICAL_WAKE_S = 90;

/**
 * Holds the app until the backend reports ready. A hosted backend on a free plan sleeps when
 * idle and takes a minute or two to start, so this shows progress instead of failed requests.
 */
export function ServerGate({ children }) {
  const [phase, setPhase] = useState('checking'); // checking | unreachable | starting | ready | failed
  const [visible, setVisible] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const started = Date.now();
    const show = setTimeout(() => setVisible(true), SHOW_AFTER_MS);
    const tick = setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);

    (async () => {
      while (!cancelled) {
        const status = await serverStatus();
        if (cancelled) return;
        if (status === 'ready') {
          setPhase('ready');
          return;
        }
        if (Date.now() - started > GIVE_UP_MS) {
          setPhase('failed');
          return;
        }
        setPhase(status);
        await new Promise((r) => setTimeout(r, RETRY_MS));
      }
    })();

    return () => {
      cancelled = true;
      clearTimeout(show);
      clearInterval(tick);
    };
  }, [attempt]);

  if (phase === 'ready') return children;
  if (!visible) return null;

  const progress = Math.min(95, Math.round((elapsed / TYPICAL_WAKE_S) * 100));
  const reached = phase === 'starting';
  const steps = [
    { label: 'Waking the server', done: reached, active: !reached },
    { label: 'Connecting to the database', done: false, active: reached },
    { label: 'Opening RosterSync', done: false, active: false },
  ];

  return (
    <div className="wake">
      <div className="wake-box card">
        <BrandMark size={44} />
        {phase === 'failed' ? (
          <>
            <h1>The server didn't respond</h1>
            <p className="muted">It may still be starting, or it may be down. Try again in a moment.</p>
            <button
              className="btn btn-primary btn-block"
              onClick={() => {
                setPhase('checking');
                setElapsed(0);
                setAttempt((a) => a + 1);
              }}
            >
              <RotateCw size={16} /> Try again
            </button>
          </>
        ) : (
          <>
            <h1>Starting RosterSync</h1>
            <p className="muted">The server sleeps when nobody is using it and is waking up now. This usually takes about a minute.</p>
            <div className="wake-bar" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
              <span style={{ width: `${progress}%` }} />
            </div>
            <ul className="wake-steps">
              {steps.map((s) => (
                <li key={s.label} className={s.done ? 'done' : s.active ? 'active' : ''}>
                  <span className="wake-dot">{s.done ? <Check size={13} /> : s.active ? <Loader2 size={13} className="spin" /> : null}</span>
                  {s.label}
                </li>
              ))}
            </ul>
            <p className="small muted">{elapsed}s elapsed</p>
          </>
        )}
      </div>
    </div>
  );
}
