import { useEffect, useState } from 'react';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Accessibility, Building2, CarFront, Clock, Lock, MapPin, Search, UserRoundX, Users } from 'lucide-react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Card, Drawer, Empty, PageHead, Skeleton, StatusPill, useToast } from '../components/ui.jsx';
import { ago, dateLabel, initials, shiftLabel } from '../lib/format.js';

function useDebounced(value, ms = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return debounced;
}

function CommuteEditor({ employee, onSaved }) {
  const toast = useToast();
  const [pickupPoint, setPickupPoint] = useState(employee.transport.pickupPoint ?? '');
  const [specialAssistance, setSpecialAssistance] = useState(employee.transport.specialAssistance ?? '');
  const dirty = pickupPoint !== (employee.transport.pickupPoint ?? '') || specialAssistance !== (employee.transport.specialAssistance ?? '');

  const save = useMutation({
    mutationFn: () =>
      api(`/v1/employees/${employee.employeeId}/transport`, {
        method: 'PATCH',
        body: { version: employee.transportVersion, pickupPoint: pickupPoint.trim() || null, specialAssistance: specialAssistance.trim() || null },
      }),
    onSuccess: () => {
      toast.success('Commute preferences saved.');
      onSaved();
    },
    onError: (err) => {
      toast.error(err);
      if (err.code === 'STALE_TRANSPORT_VERSION') onSaved();
    },
  });

  return (
    <form
      className="stack"
      style={{ gap: 12 }}
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <div>
        <label className="label" htmlFor="pickup">
          Pickup point
        </label>
        <input id="pickup" className="input" value={pickupPoint} onChange={(e) => setPickupPoint(e.target.value)} placeholder="Landmark, area" />
      </div>
      <div>
        <label className="label" htmlFor="assist">
          Special assistance
        </label>
        <input id="assist" className="input" value={specialAssistance} onChange={(e) => setSpecialAssistance(e.target.value)} placeholder="e.g. Wheelchair accessible cab" />
        <p className="hint">HR updates never touch these two fields. They belong to the transport team.</p>
      </div>
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        <button className="btn btn-primary btn-sm" disabled={!dirty || save.isPending || !employee.status || employee.status !== 'ACTIVE'}>
          {save.isPending ? 'Saving…' : 'Save preferences'}
        </button>
      </div>
    </form>
  );
}

function EmployeeDrawer({ employeeId, onClose }) {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const employee = useQuery({ queryKey: ['employee', employeeId], queryFn: () => api(`/v1/employees/${employeeId}`) });
  const bookings = useQuery({ queryKey: ['employee-bookings', employeeId], queryFn: () => api(`/v1/employees/${employeeId}/bookings`) });
  const e = employee.data;
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['employee', employeeId] });

  return (
    <Drawer onClose={onClose}>
      <div className="drawer-head">
        <span className="avatar lg">{initials(e?.hr.fullName ?? employeeId)}</span>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 700, fontSize: 17 }}>{e?.hr.fullName ?? employeeId}</div>
          <div className="small muted">
            {employeeId} {e && `· HR version ${e.hrVersion}`}
          </div>
        </div>
        {e && <StatusPill status={e.status} />}
        <button className="btn btn-secondary btn-sm" onClick={onClose}>
          Close
        </button>
      </div>

      <div className="drawer-body">
        {!e ? (
          <Skeleton height={180} />
        ) : (
          <>
            {e.status === 'INACTIVE' && (
              <div className="banner banner-amber small">
                <UserRoundX size={18} style={{ color: 'var(--amber)', flexShrink: 0 }} />
                <div>
                  {e.inactiveReason === 'MISSING_FROM_EXPORT'
                    ? 'Deactivated because an approved nightly export no longer listed this person. They come back automatically if a later export includes them.'
                    : 'HR marked this person as having left. Future rides were cancelled and their seats released.'}
                </div>
              </div>
            )}

            <Card title="From HR" sub="Owned by your HR system. Read-only here." icon={<Lock size={15} className="muted" />}>
              <dl className="field-grid">
                <dt>Email</dt>
                <dd>{e.hr.email ?? '—'}</dd>
                <dt>Phone</dt>
                <dd>{e.hr.phone ?? '—'}</dd>
                <dt>Office</dt>
                <dd>{e.hr.officeCode ?? '—'}</dd>
                <dt>Shift</dt>
                <dd>{shiftLabel(e.hr.shiftCode)}</dd>
                <dt>Manager</dt>
                <dd>{e.hr.managerId ?? '—'}</dd>
              </dl>
            </Card>

            <Card title="Commute preferences" sub="Owned by the transport admin" icon={<CarFront size={15} className="muted" />}>
              {user.role === 'ADMIN' ? (
                <CommuteEditor key={e.transportVersion} employee={e} onSaved={refresh} />
              ) : (
                <dl className="field-grid">
                  <dt>Pickup point</dt>
                  <dd>{e.transport.pickupPoint ?? 'Not set'}</dd>
                  <dt>Special assistance</dt>
                  <dd>{e.transport.specialAssistance ?? 'None'}</dd>
                </dl>
              )}
            </Card>
          </>
        )}

        <Card title="Upcoming rides" icon={<Clock size={15} className="muted" />} pad={false}>
          {!bookings.data ? (
            <div className="card-pad">
              <Skeleton />
            </div>
          ) : bookings.data.bookings.length === 0 ? (
            <Empty title="No upcoming rides">Nothing booked from today onwards.</Empty>
          ) : (
            <table className="table">
              <tbody>
                {bookings.data.bookings.map((b) => (
                  <tr key={b.id}>
                    <td style={{ fontWeight: 600 }}>{dateLabel(b.rideDate)}</td>
                    <td className="muted small">
                      {b.direction === 'PICKUP' ? 'Home → office' : 'Office → home'} · {shiftLabel(b.shiftCode)}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <StatusPill status={b.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>
    </Drawer>
  );
}

export function Employees() {
  const { user } = useAuth();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [office, setOffice] = useState('');
  const [selected, setSelected] = useState(null);
  const q = useDebounced(search);
  const offices = useQuery({ queryKey: ['offices'], queryFn: () => api('/v1/offices'), staleTime: 300_000 });

  const list = useInfiniteQuery({
    queryKey: ['employees', q, status, office],
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: '50' });
      if (q) params.set('search', q);
      if (status) params.set('status', status);
      if (office) params.set('office', office);
      if (pageParam) params.set('after', pageParam);
      return api(`/v1/employees?${params}`);
    },
    initialPageParam: null,
    getNextPageParam: (last) => last.nextCursor,
  });
  const rows = list.data?.pages.flatMap((p) => p.employees) ?? [];

  return (
    <>
      <PageHead
        title="Employees"
        sub={
          user.role === 'OPERATOR'
            ? 'Everyone HR has sent us. As an operator you see ids and masked contact details.'
            : 'Everyone HR has sent us. Open a person to update their pickup point or assistance needs.'
        }
      />

      <div className="card" style={{ marginBottom: 16, padding: 14 }}>
        <div className="row" style={{ flexWrap: 'wrap', gap: 12 }}>
          <div className="search" style={{ flex: '1 1 260px' }}>
            <Search size={16} />
            <input className="input" placeholder="Search by name or employee id" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
          <div className="chips">
            {[
              ['ACTIVE', 'Active'],
              ['INACTIVE', 'Left'],
              ['', 'Everyone'],
            ].map(([value, label]) => (
              <button key={label} className={`chip ${status === value ? 'on' : ''}`} onClick={() => setStatus(value)}>
                {label}
              </button>
            ))}
          </div>
          <select className="select" style={{ width: 220 }} value={office} onChange={(e) => setOffice(e.target.value)} aria-label="Office">
            <option value="">All offices</option>
            {offices.data?.offices.map((o) => (
              <option key={o.code} value={o.code}>
                {o.city} · {o.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="card table-wrap">
        {list.isLoading ? (
          <div className="card-pad stack">
            {[0, 1, 2, 3, 4].map((i) => (
              <Skeleton key={i} height={28} />
            ))}
          </div>
        ) : rows.length === 0 ? (
          <Empty icon={<Users size={22} />} title="No one matches these filters">
            Try a different name, id or office.
          </Empty>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>Employee</th>
                <th>Office</th>
                <th>Shift</th>
                <th>Commute</th>
                <th>Status</th>
                <th>Last change</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.employeeId} className="clickable" onClick={() => setSelected(e.employeeId)}>
                  <td>
                    <div className="person">
                      <span className="avatar">{initials(e.hr.fullName ?? e.employeeId)}</span>
                      <div>
                        <div className="person-name">{e.hr.fullName ?? 'Unknown (exit received first)'}</div>
                        <div className="person-id">{e.employeeId}</div>
                      </div>
                    </div>
                  </td>
                  <td>
                    <span className="row small" style={{ gap: 6 }}>
                      <Building2 size={14} className="muted" />
                      {e.hr.officeCode ?? '—'}
                    </span>
                  </td>
                  <td className="small">{shiftLabel(e.hr.shiftCode)}</td>
                  <td>
                    <span className="row small muted" style={{ gap: 8 }}>
                      {e.transport.pickupPoint && <MapPin size={14} aria-label="Pickup point set" />}
                      {e.transport.specialAssistance && <Accessibility size={14} aria-label="Needs special assistance" />}
                      {!e.transport.pickupPoint && !e.transport.specialAssistance && '—'}
                    </span>
                  </td>
                  <td>
                    <StatusPill status={e.status} />
                  </td>
                  <td className="small muted">{ago(e.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {list.hasNextPage && (
        <div className="row" style={{ justifyContent: 'center', marginTop: 16 }}>
          <button className="btn btn-secondary" onClick={() => list.fetchNextPage()} disabled={list.isFetchingNextPage}>
            {list.isFetchingNextPage ? 'Loading…' : 'Show more'}
          </button>
        </div>
      )}

      {selected && <EmployeeDrawer employeeId={selected} onClose={() => setSelected(null)} />}
    </>
  );
}
