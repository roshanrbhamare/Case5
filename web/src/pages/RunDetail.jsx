import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, ArrowLeft, Check, CheckCircle2, FileWarning, Loader2, XCircle } from 'lucide-react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Card, Modal, PageHead, Skeleton, StatusPill, useToast } from '../components/ui.jsx';
import { dateTime, num, pct } from '../lib/format.js';

const IN_PROGRESS = ['STAGING', 'QUEUED', 'ANALYZING', 'APPROVED', 'APPLYING'];

function Steps({ status }) {
  const checked = !['STAGING', 'QUEUED', 'ANALYZING'].includes(status);
  const applied = status === 'COMPLETED';
  const steps = [
    { label: 'Received', state: 'done' },
    { label: 'Checked', state: checked ? 'done' : status === 'FAILED' ? 'warn' : 'current' },
    ...(status === 'BLOCKED' || status === 'APPROVED' || status === 'REJECTED' || (status === 'APPLYING' && false)
      ? [{ label: status === 'BLOCKED' ? 'Waiting for review' : status === 'REJECTED' ? 'Rejected' : 'Approved', state: status === 'BLOCKED' ? 'warn' : 'done' }]
      : []),
    { label: status === 'REJECTED' ? 'Not applied' : 'Applied', state: applied ? 'done' : ['APPLYING', 'APPROVED'].includes(status) ? 'current' : 'todo' },
  ];
  return (
    <div className="steps">
      {steps.map((s, i) => (
        <div key={s.label} className="row" style={{ flex: i < steps.length - 1 ? 1 : 'none', gap: 0 }}>
          <div className={`step ${s.state}`}>
            <span className="bullet">{s.state === 'done' ? <Check size={13} /> : s.state === 'warn' ? '!' : i + 1}</span>
            {s.label}
          </div>
          {i < steps.length - 1 && <div className={`step-line ${s.state === 'done' ? 'done' : ''}`} />}
        </div>
      ))}
    </div>
  );
}

function BrakeCard({ run, canDecide, onDecide }) {
  const ratio = run.missingRatio ?? 0;
  return (
    <div className="card" style={{ borderColor: 'var(--amber-100)', overflow: 'hidden' }}>
      <div className="banner banner-amber" style={{ borderRadius: 0, border: 'none' }}>
        <span className="banner-icon" style={{ color: 'var(--amber)' }}>
          <AlertTriangle size={20} />
        </span>
        <div>
          <div className="banner-title">Paused: this export looks incomplete</div>
          <div className="banner-body">
            It doesn't mention <strong>{num(run.missingCount)}</strong> of the <strong>{num(run.activeBefore)}</strong> people who are active today. That is far more
            than a normal night of exits, so nothing has been applied and nobody has lost their cab.
          </div>
        </div>
      </div>
      <div className="card-pad stack" style={{ gap: 18 }}>
        <div>
          <div className="spread small" style={{ marginBottom: 8 }}>
            <span style={{ fontWeight: 600 }}>Missing from this file: {pct(ratio, 1)}</span>
            <span className="muted">Your safety limit: {pct(run.threshold)}</span>
          </div>
          <div className="meter" style={{ height: 10 }}>
            <span style={{ width: `${Math.min(100, ratio * 100)}%`, background: 'var(--amber)' }} />
            <span className="limit" style={{ left: `${run.threshold * 100}%` }} />
          </div>
        </div>
        <p className="muted">
          This usually means the export was cut off or a filter went wrong upstream. Check with HR first. If the departures are real (for example a business unit was
          sold), approve the run and the {num(run.missingCount)} people will be deactivated and their future rides cancelled.
        </p>
        {canDecide ? (
          <div className="row" style={{ justifyContent: 'flex-end' }}>
            <button className="btn btn-danger" onClick={() => onDecide('reject')}>
              <XCircle size={16} /> Reject this file
            </button>
            <button className="btn btn-primary" onClick={() => onDecide('approve')}>
              <CheckCircle2 size={16} /> Approve and apply
            </button>
          </div>
        ) : (
          <p className="small muted">Only a sync operator can approve or reject a paused export.</p>
        )}
      </div>
    </div>
  );
}

function DecisionModal({ kind, run, onClose }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [note, setNote] = useState('');
  const decide = useMutation({
    mutationFn: () => api(`/v1/reconcile/runs/${run.id}/${kind}`, { method: 'POST', body: { note } }),
    onSuccess: () => {
      toast.success(kind === 'approve' ? 'Approved. Applying the export now.' : 'Rejected. Nothing was changed.');
      queryClient.invalidateQueries({ queryKey: ['run', run.id] });
      queryClient.invalidateQueries({ queryKey: ['ops-summary'] });
      onClose();
    },
    onError: (err) => toast.error(err),
  });
  const approve = kind === 'approve';
  return (
    <Modal open onClose={onClose}>
      <h3>{approve ? `Deactivate ${num(run.missingCount)} people?` : 'Reject this export?'}</h3>
      <p className="muted" style={{ marginBottom: 16 }}>
        {approve
          ? 'They will be marked as left and their upcoming rides cancelled. Everyone else in the file is updated as usual.'
          : 'Nothing from this file will be applied. HR can send a corrected export.'}
      </p>
      <label className="label" htmlFor="note">
        Note (saved with the decision)
      </label>
      <textarea
        id="note"
        className="textarea"
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder={approve ? 'e.g. Confirmed with HR: the Pune unit was transferred to a partner.' : 'e.g. File was cut off at 120k rows; HR is resending.'}
      />
      <div className="row" style={{ justifyContent: 'flex-end', marginTop: 16 }}>
        <button className="btn btn-secondary" onClick={onClose}>
          Cancel
        </button>
        <button className={`btn ${approve ? 'btn-primary' : 'btn-danger'}`} disabled={note.trim().length < 3 || decide.isPending} onClick={() => decide.mutate()}>
          {decide.isPending ? 'Saving…' : approve ? 'Approve and apply' : 'Reject file'}
        </button>
      </div>
    </Modal>
  );
}

export function RunDetail() {
  const { runId } = useParams();
  const { user } = useAuth();
  const [deciding, setDeciding] = useState(null);
  const { data: run, isLoading } = useQuery({
    queryKey: ['run', runId],
    queryFn: () => api(`/v1/reconcile/runs/${runId}`),
    refetchInterval: (q) => (IN_PROGRESS.includes(q.state.data?.status) ? 1500 : false),
  });

  if (isLoading || !run) return <Skeleton height={300} />;
  const p = run.plan;
  const busy = IN_PROGRESS.includes(run.status);

  return (
    <>
      <Link to="/nightly" className="btn btn-ghost btn-sm" style={{ marginBottom: 12, marginLeft: -10 }}>
        <ArrowLeft size={15} /> Nightly files
      </Link>
      <PageHead
        title={`Export of ${num(run.fileRows)} rows`}
        sub={`Received ${dateTime(run.createdAt)} · run ${run.id.slice(0, 8)}`}
        actions={<StatusPill status={run.status} />}
      />

      <div className="stack">
        <div className="card card-pad">
          <Steps status={run.status} />
          {busy && (
            <div className="row muted small" style={{ marginTop: 14 }}>
              <Loader2 size={15} className="spin" style={{ animation: 'spin 1s linear infinite' }} />
              {run.status === 'APPLYING' || run.status === 'APPROVED' ? 'Applying changes bucket by bucket…' : 'Comparing the file with your roster…'}
              <style>{'@keyframes spin { to { transform: rotate(360deg); } }'}</style>
            </div>
          )}
        </div>

        {run.status === 'BLOCKED' && <BrakeCard run={run} canDecide={user.role === 'OPERATOR'} onDecide={setDeciding} />}

        {run.status === 'COMPLETED' && (
          <div className="banner banner-green">
            <span className="banner-icon" style={{ color: 'var(--green)' }}>
              <CheckCircle2 size={20} />
            </span>
            <div>
              <div className="banner-title">{run.appliedChanges ? `Applied ${num(run.appliedChanges)} changes` : 'Already in sync: nothing to change'}</div>
              <div className="banner-body">
                {run.bucketsSkipped === 256 ? 'Every bucket matched, so no row needed a closer look.' : `${num(run.bucketsSkipped)} of 256 buckets matched exactly and were skipped.`}
                {run.decidedBy && ` Approved with the note: "${run.decisionNote}"`}
              </div>
            </div>
          </div>
        )}

        {run.status === 'REJECTED' && (
          <div className="banner banner-red">
            <span className="banner-icon" style={{ color: 'var(--red)' }}>
              <XCircle size={20} />
            </span>
            <div>
              <div className="banner-title">Rejected, nothing applied</div>
              <div className="banner-body">Note: "{run.decisionNote}"</div>
            </div>
          </div>
        )}

        {run.status === 'FAILED' && (
          <div className="banner banner-red">
            <span className="banner-icon" style={{ color: 'var(--red)' }}>
              <FileWarning size={20} />
            </span>
            <div>
              <div className="banner-title">This file couldn't be processed</div>
              <div className="banner-body">Reason: {run.failureCode?.replace(/_/g, ' ').toLowerCase()}. Nothing was changed; HR can send it again.</div>
            </div>
          </div>
        )}

        {p.creates !== null && (
          <Card title="What this file means for your roster" sub="Worked out before anything is written">
            <div className="plan">
              <div>
                <div className="k">New joiners</div>
                <div className="v num">{num(p.creates)}</div>
              </div>
              <div>
                <div className="k">Updated details</div>
                <div className="v num">{num(p.updates)}</div>
              </div>
              <div>
                <div className="k">Back after leaving</div>
                <div className="v num">{num(p.reactivations)}</div>
              </div>
              <div>
                <div className="k">Leaving (not in file)</div>
                <div className="v num" style={{ color: run.status === 'BLOCKED' ? 'var(--amber)' : undefined }}>
                  {num(p.deactivations)}
                </div>
              </div>
              <div>
                <div className="k">Unchanged</div>
                <div className="v num">{num(p.unchanged)}</div>
              </div>
              <div>
                <div className="k">Older than live updates</div>
                <div className="v num">{num(p.stale)}</div>
              </div>
              <div>
                <div className="k">Conflicting versions</div>
                <div className="v num">{num(p.conflicts)}</div>
              </div>
              <div>
                <div className="k">Rows rejected</div>
                <div className="v num" style={{ color: run.rejectedRows ? 'var(--red)' : undefined }}>
                  {num(run.rejectedRows + (run.duplicateRows ?? 0))}
                </div>
              </div>
            </div>
            {(run.rejectedRows > 0 || run.duplicateRows > 0) && (
              <div className="row" style={{ marginTop: 14, justifyContent: 'flex-end' }}>
                <Link to={`/activity/${run.batchId}`} className="btn btn-secondary btn-sm">
                  See rejected rows
                </Link>
              </div>
            )}
          </Card>
        )}
      </div>

      {deciding && <DecisionModal kind={deciding} run={run} onClose={() => setDeciding(null)} />}
    </>
  );
}
