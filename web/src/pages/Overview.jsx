import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, ArrowRight, CheckCircle2, CircleSlash, Inbox, Repeat2, Send, Users } from 'lucide-react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Card, Empty, PageHead, Skeleton, StatCard, StatusPill } from '../components/ui.jsx';
import { ago, duration, errorText, greeting, num, pct } from '../lib/format.js';

function HourlyChart({ hourly }) {
  const byHour = new Map(hourly.map((h) => [new Date(h.hour).getHours() + new Date(h.hour).getDate() * 100, h]));
  const now = new Date();
  const slots = Array.from({ length: 24 }, (_, i) => {
    const d = new Date(now.getTime() - (23 - i) * 3_600_000);
    return { label: d.getHours(), ...(byHour.get(d.getHours() + d.getDate() * 100) ?? { applied: 0, skipped: 0, rejected: 0 }) };
  });
  const max = Math.max(1, ...slots.map((s) => s.applied + s.skipped + s.rejected));
  const total = slots.reduce((sum, s) => sum + s.applied + s.skipped + s.rejected, 0);

  return (
    <>
      {total === 0 ? (
        <Empty icon={<Inbox size={22} />} title="No HR changes in the last 24 hours">
          Events from the live HR feed will show up here. Nightly files have their own card below.
        </Empty>
      ) : (
        <div className="bars" aria-label="HR changes per hour, last 24 hours">
          {slots.map((s, i) => (
            <div className="bar" key={i} title={`${s.label}:00 · ${s.applied} applied, ${s.skipped} skipped, ${s.rejected} rejected`}>
              <span className="ok" style={{ height: `${(s.applied / max) * 100}%` }} />
              <span className="skip" style={{ height: `${(s.skipped / max) * 100}%` }} />
              <span className="bad" style={{ height: `${(s.rejected / max) * 100}%` }} />
            </div>
          ))}
        </div>
      )}
      <div className="spread" style={{ marginTop: 12 }}>
        <div className="legend">
          <span>
            <i style={{ background: 'var(--green)' }} />
            Applied
          </span>
          <span>
            <i style={{ background: '#a9d99c' }} />
            Skipped safely
          </span>
          <span>
            <i style={{ background: '#e9837d' }} />
            Rejected
          </span>
        </div>
        <span className="small muted">Last 24 hours</span>
      </div>
    </>
  );
}

function DeliveryHealth({ outbox, consumer }) {
  const backlog = outbox.pending > 50 || outbox.oldestPendingSeconds > 60;
  const rows = [
    ['Waiting to publish', num(outbox.pending), 'Changes saved but not yet sent to downstream systems'],
    ['Oldest waiting', outbox.pending ? duration(outbox.oldestPendingSeconds) : '—', 'How far behind the event stream is'],
    ['Needs attention', num(outbox.dead), 'Events that could not be published after retries'],
    ['Booking updates pending', consumer ? num(consumer.pending) : 'Unavailable', 'Exits not yet applied to cab bookings'],
  ];
  return (
    <Card
      title="Downstream delivery"
      sub="Every saved change is published through the outbox"
      actions={<StatusPill status={backlog || outbox.dead ? 'BLOCKED' : 'ACTIVE'} label={backlog ? 'Catching up' : outbox.dead ? 'Check dead letters' : 'Healthy'} />}
    >
      <div className="stack" style={{ gap: 14 }}>
        {rows.map(([k, v, hint]) => (
          <div className="spread" key={k}>
            <div>
              <div style={{ fontWeight: 600 }}>{k}</div>
              <div className="small muted">{hint}</div>
            </div>
            <div className="num" style={{ fontWeight: 700, fontSize: 16 }}>
              {v}
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

export function Overview() {
  const { user } = useAuth();
  const { data, isLoading } = useQuery({ queryKey: ['ops-summary'], queryFn: () => api('/v1/ops/summary'), refetchInterval: 10_000 });
  const firstName = user.displayName.split(' ')[0];

  return (
    <>
      <PageHead title={`${greeting()}, ${firstName}`} sub={`Here's how ${user.tenantName}'s roster sync is doing right now.`} />

      {isLoading || !data ? (
        <div className="grid grid-4">
          {[0, 1, 2, 3].map((i) => (
            <div className="card stat" key={i}>
              <Skeleton width="50%" />
              <Skeleton height={30} width="40%" />
            </div>
          ))}
        </div>
      ) : (
        <div className="stack">
          {data.blockedRun ? (
            <div className="banner banner-amber">
              <span className="banner-icon" style={{ color: 'var(--amber)' }}>
                <AlertTriangle size={20} />
              </span>
              <div style={{ flex: 1 }}>
                <div className="banner-title">A nightly export is paused and waiting for you</div>
                <div className="banner-body">
                  It's missing {num(data.blockedRun.missingCount)} of {num(data.blockedRun.activeBefore)} active employees ({pct(data.blockedRun.missingRatio)}). Nobody has been changed
                  while it waits.
                </div>
              </div>
              <Link to={`/nightly/${data.blockedRun.id}`} className="btn btn-primary btn-sm">
                Review <ArrowRight size={15} />
              </Link>
            </div>
          ) : (
            <div className="banner banner-green">
              <span className="banner-icon" style={{ color: 'var(--green)' }}>
                <CheckCircle2 size={20} />
              </span>
              <div>
                <div className="banner-title">All quiet</div>
                <div className="banner-body">No exports are waiting on a decision. Changes are flowing normally.</div>
              </div>
            </div>
          )}

          <div className="grid grid-4">
            <StatCard label="Active employees" value={num(data.employees.active)} icon={<Users size={17} />} foot={`${num(data.employees.inactive)} inactive`} />
            <StatCard
              label="Changes applied · 24h"
              value={num(data.last24h.applied)}
              icon={<CheckCircle2 size={17} />}
              foot={`${num(data.last24h.liveApplied)} from ${num(data.last24h.received)} live events · ${num(data.last24h.nightlyApplied)} from ${num(data.last24h.files)} nightly ${data.last24h.files === 1 ? 'file' : 'files'}`}
            />
            <StatCard
              label="Skipped safely · 24h"
              value={num(data.last24h.stale + data.last24h.duplicate)}
              icon={<Repeat2 size={17} />}
              tone="violet"
              foot={`${num(data.last24h.duplicate)} repeats · ${num(data.last24h.stale)} late arrivals`}
            />
            <StatCard
              label="Bad records · 24h"
              value={num(data.last24h.rejected)}
              icon={<CircleSlash size={17} />}
              tone={data.last24h.rejected ? 'red' : ''}
              foot={data.topErrors[0] ? `Mostly: ${errorText(data.topErrors[0].code).toLowerCase()}` : 'No bad records'}
            />
          </div>

          <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1.6fr) minmax(0, 1fr)' }}>
            <Card title="Live HR changes by hour" sub="What happened to each event from the HR feed">
              <HourlyChart hourly={data.hourly} />
            </Card>
            <DeliveryHealth outbox={data.outbox} consumer={data.consumer} />
          </div>

          <div className="grid grid-2">
            <Card
              title="Recent nightly files"
              pad={false}
              actions={
                <Link to="/nightly" className="btn btn-ghost btn-sm">
                  See all <ArrowRight size={15} />
                </Link>
              }
            >
              {data.recentRuns.length === 0 ? (
                <Empty title="No nightly files yet">When HR sends tonight's export, it will appear here.</Empty>
              ) : (
                <table className="table">
                  <tbody>
                    {data.recentRuns.map((r) => (
                      <tr key={r.id}>
                        <td>
                          <Link to={`/nightly/${r.id}`} style={{ fontWeight: 600 }}>
                            {num(r.fileRows)} rows
                          </Link>
                          <div className="small muted">{ago(r.createdAt)}</div>
                        </td>
                        <td className="small muted">{r.appliedChanges !== null ? `${num(r.appliedChanges)} changes applied` : r.missingCount !== null ? `${num(r.missingCount)} missing` : ''}</td>
                        <td style={{ textAlign: 'right' }}>
                          <StatusPill status={r.status} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </Card>

            <Card title="Why records were rejected" sub="Last 24 hours, by reason" icon={<Send size={16} className="muted" />}>
              {data.topErrors.length === 0 ? (
                <Empty icon={<CheckCircle2 size={22} />} title="Nothing rejected">
                  Every record that arrived passed validation.
                </Empty>
              ) : (
                <div className="stack" style={{ gap: 12 }}>
                  {data.topErrors.map((e) => {
                    const max = data.topErrors[0].n;
                    return (
                      <div key={e.code}>
                        <div className="spread small" style={{ marginBottom: 6 }}>
                          <span style={{ fontWeight: 600 }}>{errorText(e.code)}</span>
                          <span className="num muted">{num(e.n)}</span>
                        </div>
                        <div className="meter">
                          <span style={{ width: `${(e.n / max) * 100}%`, background: '#e9837d' }} />
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </Card>
          </div>
        </div>
      )}
    </>
  );
}
