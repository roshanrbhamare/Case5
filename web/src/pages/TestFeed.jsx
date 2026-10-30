import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowDownUp, Braces, Copy, DoorOpen, FileX2, Play } from 'lucide-react';
import { api } from '../api.js';
import { Card, PageHead, StatusPill, useToast } from '../components/ui.jsx';
import { errorText } from '../lib/format.js';

const person = (id, office, overrides = {}) => ({
  fullName: 'Test Person',
  email: `${id.toLowerCase()}@test.example`,
  phone: '+919800012345',
  managerId: null,
  officeCode: office,
  shiftCode: 'S0900',
  ...overrides,
});

function buildScenarios(offices) {
  const [home = 'BLR-WF', other = home] = offices;
  const id = `T${Date.now().toString(36).toUpperCase().slice(-5)}`;
  return [
    {
      key: 'order',
      icon: <ArrowDownUp size={18} />,
      title: 'Out-of-order delivery',
      text: 'A transfer (v7) arrives before an older shift change (v6). The older one should be ignored.',
      batches: [
        [
          { type: 'CREATE', employeeId: id, version: 1, data: person(id, home) },
          { type: 'UPDATE', employeeId: id, version: 7, data: person(id, other, { shiftCode: 'S1000' }) },
          { type: 'UPDATE', employeeId: id, version: 6, data: person(id, home, { shiftCode: 'NIGHT' }) },
        ],
      ],
    },
    {
      key: 'replay',
      icon: <Copy size={18} />,
      title: 'Same feed twice',
      text: 'HR resends a batch after a timeout. The second delivery must change nothing.',
      batches: (() => {
        const feed = [
          { type: 'CREATE', employeeId: `${id}A`, version: 1, data: person(`${id}A`, home) },
          { type: 'UPDATE', employeeId: `${id}A`, version: 2, data: person(`${id}A`, home, { shiftCode: 'S1400' }) },
          { type: 'CREATE', employeeId: `${id}B`, version: 1, data: person(`${id}B`, other) },
        ];
        return [feed, feed];
      })(),
    },
    {
      key: 'bad',
      icon: <FileX2 size={18} />,
      title: 'A bad record in the batch',
      text: 'One phone number is malformed and one office code is unknown. Only those two should be rejected.',
      batches: [
        [
          { type: 'CREATE', employeeId: `${id}1`, version: 1, data: person(`${id}1`, home) },
          { type: 'CREATE', employeeId: `${id}2`, version: 1, data: person(`${id}2`, home, { phone: '12abc' }) },
          { type: 'CREATE', employeeId: `${id}3`, version: 1, data: person(`${id}3`, 'MARS-01') },
          { type: 'CREATE', employeeId: `${id}4`, version: 1, data: { ...person(`${id}4`, other), pickupPoint: 'HR should not set this' } },
        ],
      ],
    },
    {
      key: 'exit',
      icon: <DoorOpen size={18} />,
      title: 'Someone leaves',
      text: "An exit for E1003. Their future rides are cancelled and seats freed by the booking worker, once.",
      batches: [[{ type: 'DEACTIVATE', employeeId: 'E1003', version: Math.floor(Date.now() / 1000) }]],
    },
  ];
}

function Results({ runs }) {
  return runs.map((run, b) => (
    <Card
      key={b}
      title={runs.length > 1 ? `Delivery ${b + 1}` : 'Result'}
      sub={`${run.summary.applied} applied · ${run.summary.duplicate} repeats · ${run.summary.stale} late · ${run.summary.rejected} rejected`}
      pad={false}
    >
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr>
              <th>#</th>
              <th>Employee</th>
              <th>Outcome</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {run.results.map((r) => (
              <tr key={r.index}>
                <td className="num muted">{r.index + 1}</td>
                <td className="mono">{r.employeeId ?? '—'}</td>
                <td>
                  <StatusPill status={r.outcome === 'REJECTED' ? 'FAILED' : r.outcome} label={r.outcome === 'REJECTED' ? 'Rejected' : undefined} />
                </td>
                <td className="small">
                  {r.errors?.map((e) => errorText(e.code)).join('; ')}
                  {r.changedFields && <span className="muted">Changed: {r.changedFields.join(', ')}</span>}
                  {r.warnings && <div className="muted">Ignored fields HR doesn't own: {r.warnings.map((w) => w.field).join(', ')}</div>}
                  {r.outcome === 'STALE' && <span className="muted">A newer version was already applied</span>}
                  {r.outcome === 'DUPLICATE' && <span className="muted">This exact version was already applied{r.conflict ? ' (with different content: flagged)' : ''}</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  ));
}

export function TestFeed() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const offices = useQuery({ queryKey: ['offices'], queryFn: () => api('/v1/offices'), staleTime: 300_000 });
  const scenarios = useMemo(() => buildScenarios(offices.data?.offices.map((o) => o.code) ?? []), [offices.data]);
  const [selected, setSelected] = useState('order');
  const [custom, setCustom] = useState('');
  const [results, setResults] = useState(null);
  const scenario = scenarios.find((s) => s.key === selected);

  const send = useMutation({
    mutationFn: async (batches) => {
      const out = [];
      for (const events of batches) out.push(await api('/v1/events', { method: 'POST', body: { events } }));
      return out;
    },
    onSuccess: (out) => {
      setResults(out);
      queryClient.invalidateQueries();
    },
    onError: (err) => toast.error(err),
  });

  function sendCustom() {
    try {
      const parsed = JSON.parse(custom);
      send.mutate([Array.isArray(parsed) ? parsed : parsed.events]);
    } catch {
      toast.error({ message: "That isn't valid JSON. Paste an array of events or {\"events\": [...]}" });
    }
  }

  return (
    <>
      <PageHead
        title="Test feed"
        sub="Send realistic HR events through the real pipeline and watch how they're handled. Everything here goes through the same API the HR system uses."
      />
      <div className="grid grid-4" style={{ marginBottom: 16 }}>
        {scenarios.map((s) => (
          <button
            key={s.key}
            className={`card scenario ${selected === s.key ? 'on' : ''}`}
            onClick={() => {
              setSelected(s.key);
              setResults(null);
            }}
          >
            <span className="scenario-icon">{s.icon}</span>
            <span style={{ fontWeight: 650 }}>{s.title}</span>
            <span className="small muted">{s.text}</span>
          </button>
        ))}
      </div>

      <div className="grid grid-2" style={{ alignItems: 'start' }}>
        <div className="stack">
          <Card
            title="Events to send"
            sub={scenario?.batches.length > 1 ? `Sent ${scenario.batches.length} times, one after the other` : 'Sent as one batch'}
            actions={
              <button className="btn btn-primary btn-sm" disabled={send.isPending || !scenario} onClick={() => send.mutate(scenario.batches)}>
                <Play size={15} /> {send.isPending ? 'Sending…' : 'Send'}
              </button>
            }
          >
            <pre className="code mono">{JSON.stringify(scenario?.batches[0] ?? [], null, 2)}</pre>
          </Card>
          <Card title="Your own events" icon={<Braces size={15} className="muted" />}>
            <textarea className="textarea mono" style={{ minHeight: 140 }} value={custom} onChange={(e) => setCustom(e.target.value)} placeholder='[{"type":"UPDATE","employeeId":"E1001","version":5,"data":{...}}]' />
            <div className="row" style={{ justifyContent: 'flex-end', marginTop: 12 }}>
              <button className="btn btn-secondary btn-sm" disabled={!custom.trim() || send.isPending} onClick={sendCustom}>
                Send custom batch
              </button>
            </div>
          </Card>
        </div>
        <div className="stack">
          {results ? (
            <Results runs={results} />
          ) : (
            <div className="card card-pad muted">Pick a scenario and press Send. Each record's outcome appears here.</div>
          )}
        </div>
      </div>
    </>
  );
}
