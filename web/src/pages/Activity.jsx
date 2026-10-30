import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, ShieldAlert } from 'lucide-react';
import { api } from '../api.js';
import { Card, Empty, PageHead, Skeleton, StatusPill } from '../components/ui.jsx';
import { ago, dateTime, errorText, num } from '../lib/format.js';

function actorLabel(actor) {
  if (actor.startsWith('hr-system:')) return 'HR system';
  if (actor.startsWith('user:')) return 'Operator';
  return actor;
}

function BatchErrors({ batchId }) {
  const { data, isLoading } = useQuery({ queryKey: ['batch-errors', batchId], queryFn: () => api(`/v1/batches/${batchId}/errors`) });
  if (isLoading) return <Skeleton height={120} />;
  const { batch, errors } = data;
  return (
    <Card
      title="Error report"
      sub={`${num(batch.received)} records received ${ago(batch.createdAt)} · ${num(batch.rejected)} rejected, the rest processed normally`}
      icon={<ShieldAlert size={16} className="muted" />}
      pad={false}
      actions={
        <Link to="/activity" className="btn btn-ghost btn-sm">
          <ArrowLeft size={15} /> All batches
        </Link>
      }
    >
      {errors.length === 0 ? (
        <Empty icon={<CheckCircle2 size={22} />} title="Every record in this batch was valid" />
      ) : (
        <div className="table-wrap">
          <table className="table">
            <thead>
              <tr>
                <th>Record</th>
                <th>Employee</th>
                <th>Problem</th>
                <th>Field</th>
                <th>Code</th>
              </tr>
            </thead>
            <tbody>
              {errors.map((e, i) => (
                <tr key={i}>
                  <td className="num">#{e.record}</td>
                  <td>{e.employeeId ?? <span className="muted">unreadable id</span>}</td>
                  <td style={{ fontWeight: 500 }}>{errorText(e.code)}</td>
                  <td className="muted">{e.field ?? '—'}</td>
                  <td className="mono muted">{e.code}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="small muted" style={{ padding: '12px 20px' }}>
            The report lists which field was wrong, never the value that was sent, so it is safe to share with HR.
          </p>
        </div>
      )}
    </Card>
  );
}

export function Activity() {
  const { batchId } = useParams();
  const navigate = useNavigate();
  const { data, isLoading } = useQuery({ queryKey: ['batches'], queryFn: () => api('/v1/batches'), refetchInterval: 10_000 });

  return (
    <>
      <PageHead title="Sync activity" sub="Every batch from the live HR feed and every nightly file, with what happened to each record." />
      <div className="stack">
        {batchId && <BatchErrors batchId={batchId} />}
        <Card title="Recent batches" pad={false}>
          {isLoading ? (
            <div className="card-pad">
              <Skeleton height={120} />
            </div>
          ) : data.batches.length === 0 ? (
            <Empty title="Nothing received yet">Batches appear here as soon as HR sends changes.</Empty>
          ) : (
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>Received</th>
                    <th>Source</th>
                    <th style={{ textAlign: 'right' }}>Records</th>
                    <th style={{ textAlign: 'right' }}>Applied</th>
                    <th style={{ textAlign: 'right' }}>Repeats</th>
                    <th style={{ textAlign: 'right' }}>Late</th>
                    <th style={{ textAlign: 'right' }}>Rejected</th>
                  </tr>
                </thead>
                <tbody>
                  {data.batches.map((b) => (
                    <tr key={b.id} className="clickable" onClick={() => navigate(`/activity/${b.id}`)} style={b.id === batchId ? { background: 'var(--green-50)' } : undefined}>
                      <td>
                        <div style={{ fontWeight: 600 }}>{dateTime(b.createdAt)}</div>
                        <div className="small muted">by {actorLabel(b.actor)}</div>
                      </td>
                      <td>
                        <StatusPill status={b.source} />
                      </td>
                      <td className="num" style={{ textAlign: 'right' }}>
                        {num(b.received)}
                      </td>
                      <td className="num" style={{ textAlign: 'right', color: 'var(--green-800)', fontWeight: 600 }}>
                        {b.source === 'NIGHTLY_FILE' ? '—' : num(b.applied)}
                      </td>
                      <td className="num muted" style={{ textAlign: 'right' }}>
                        {b.source === 'NIGHTLY_FILE' ? '—' : num(b.duplicate)}
                      </td>
                      <td className="num muted" style={{ textAlign: 'right' }}>
                        {b.source === 'NIGHTLY_FILE' ? '—' : num(b.stale)}
                      </td>
                      <td className="num" style={{ textAlign: 'right', color: b.rejected ? 'var(--red)' : undefined, fontWeight: b.rejected ? 700 : 400 }}>
                        {num(b.rejected)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
