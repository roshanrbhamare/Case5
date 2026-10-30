import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileSpreadsheet, ShieldCheck, UploadCloud } from 'lucide-react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Card, Empty, PageHead, Skeleton, StatusPill, useToast } from '../components/ui.jsx';
import { ago, num, pct } from '../lib/format.js';

const TEMPLATE = 'employee_id,full_name,email,phone,manager_id,office_code,shift_code,version\nE1001,Ananya Sharma,ananya.sharma@acme.example,+919810000001,,BLR-WF,S0900,12\n';

function Upload() {
  const toast = useToast();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const input = useRef(null);
  const [over, setOver] = useState(false);

  const upload = useMutation({
    mutationFn: (file) => api('/v1/reconcile/runs', { method: 'POST', raw: file, headers: { 'content-type': 'text/csv' } }),
    onSuccess: (run) => {
      toast.success('Export received. Checking it against your roster now.');
      queryClient.invalidateQueries({ queryKey: ['runs'] });
      navigate(`/nightly/${run.id}`);
    },
    onError: (err) => toast.error(err),
  });

  const pick = (file) => file && upload.mutate(file);

  return (
    <div
      className={`drop ${over ? 'over' : ''}`}
      role="button"
      tabIndex={0}
      onClick={() => input.current?.click()}
      onKeyDown={(e) => e.key === 'Enter' && input.current?.click()}
      onDragOver={(e) => {
        e.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setOver(false);
        pick(e.dataTransfer.files[0]);
      }}
    >
      <input ref={input} type="file" accept=".csv,text/csv" hidden onChange={(e) => pick(e.target.files[0])} />
      <div className="drop-icon">
        <UploadCloud size={22} />
      </div>
      <div style={{ fontWeight: 650, fontSize: 15 }}>{upload.isPending ? 'Uploading and checking rows…' : "Drop tonight's HR export here"}</div>
      <div className="muted small" style={{ marginTop: 4 }}>
        CSV, up to 200,000 employees. It is streamed row by row, so size doesn't matter.
      </div>
      <div style={{ marginTop: 14 }}>
        <a
          className="btn btn-secondary btn-sm"
          href={`data:text/csv;charset=utf-8,${encodeURIComponent(TEMPLATE)}`}
          download="rostersync-export-template.csv"
          onClick={(e) => e.stopPropagation()}
        >
          <FileSpreadsheet size={15} /> Download template
        </a>
      </div>
    </div>
  );
}

export function Nightly() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { data, isLoading } = useQuery({ queryKey: ['runs'], queryFn: () => api('/v1/reconcile/runs'), refetchInterval: 5_000 });
  const threshold = data?.runs[0]?.threshold ?? 0.3;

  return (
    <>
      <PageHead
        title="Nightly file"
        sub="Each night HR sends a full export. We compare it with what we have, apply the differences, and stop if the file looks broken."
      />

      <div className="grid" style={{ gridTemplateColumns: 'minmax(0, 1.4fr) minmax(0, 1fr)', marginBottom: 16 }}>
        {user.role === 'OPERATOR' ? (
          <Upload />
        ) : (
          <div className="card card-pad">
            <div style={{ fontWeight: 650 }}>Uploads come from the HR system</div>
            <p className="muted" style={{ marginTop: 6 }}>
              Sync operators can upload a file by hand. You can follow every run below.
            </p>
          </div>
        )}
        <div className="card card-pad">
          <div className="row" style={{ marginBottom: 10 }}>
            <span className="stat-icon">
              <ShieldCheck size={17} />
            </span>
            <div style={{ fontWeight: 650 }}>Safety brake is on</div>
          </div>
          <p className="muted">
            If an export is missing <strong style={{ color: 'var(--ink)' }}>{pct(threshold)}</strong> or more of your active employees, we pause it and nobody is
            deactivated until an operator reviews it. A truncated file can't turn off thousands of cabs overnight.
          </p>
        </div>
      </div>

      <Card title="Runs" pad={false}>
        {isLoading ? (
          <div className="card-pad">
            <Skeleton height={120} />
          </div>
        ) : data.runs.length === 0 ? (
          <Empty icon={<FileSpreadsheet size={22} />} title="No exports yet">
            The first nightly file will show up here.
          </Empty>
        ) : (
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Uploaded</th>
                  <th>Status</th>
                  <th style={{ textAlign: 'right' }}>Rows</th>
                  <th style={{ textAlign: 'right' }}>Rejected</th>
                  <th style={{ textAlign: 'right' }}>Missing</th>
                  <th style={{ textAlign: 'right' }}>Changes applied</th>
                </tr>
              </thead>
              <tbody>
                {data.runs.map((r) => (
                  <tr key={r.id} className="clickable" onClick={() => navigate(`/nightly/${r.id}`)}>
                    <td>
                      <div style={{ fontWeight: 600 }}>{ago(r.createdAt)}</div>
                      <div className="small muted mono">{r.id.slice(0, 8)}</div>
                    </td>
                    <td>
                      <StatusPill status={r.status} />
                    </td>
                    <td className="num" style={{ textAlign: 'right' }}>
                      {num(r.fileRows)}
                    </td>
                    <td className="num" style={{ textAlign: 'right' }}>
                      {num(r.rejectedRows)}
                    </td>
                    <td className="num" style={{ textAlign: 'right', color: r.status === 'BLOCKED' ? 'var(--amber)' : undefined, fontWeight: r.status === 'BLOCKED' ? 700 : 400 }}>
                      {r.missingCount === null ? '—' : `${num(r.missingCount)} (${pct(r.missingRatio)})`}
                    </td>
                    <td className="num" style={{ textAlign: 'right' }}>
                      {num(r.appliedChanges)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
