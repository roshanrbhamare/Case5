import { useQuery } from '@tanstack/react-query';
import { Accessibility, Building2, CalendarClock, MapPin, UserRoundX } from 'lucide-react';
import { api } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Card, Empty, PageHead, Skeleton, StatusPill } from '../components/ui.jsx';
import { dateLabel, greeting, shiftLabel } from '../lib/format.js';

export function MyCommute() {
  const { user } = useAuth();
  const { data, isLoading } = useQuery({ queryKey: ['me'], queryFn: () => api('/v1/me') });

  return (
    <>
      <PageHead title={`${greeting()}, ${user.displayName.split(' ')[0]}`} sub="Your office commute at a glance." />
      {isLoading || !data ? (
        <Skeleton height={240} />
      ) : (
        <div className="stack">
          {data.employee.status === 'INACTIVE' && (
            <div className="banner banner-amber">
              <UserRoundX size={20} style={{ color: 'var(--amber)' }} />
              <div>Your profile is no longer active, so no cabs are scheduled. If this is a mistake, contact your HR team.</div>
            </div>
          )}
          <div className="grid grid-3">
            <div className="card stat">
              <div className="stat-top">
                <span className="stat-label">Office</span>
                <span className="stat-icon">
                  <Building2 size={17} />
                </span>
              </div>
              <div style={{ fontWeight: 700, fontSize: 18 }}>{data.employee.hr.officeCode}</div>
              <div className="stat-foot">{shiftLabel(data.employee.hr.shiftCode)}</div>
            </div>
            <div className="card stat">
              <div className="stat-top">
                <span className="stat-label">Pickup point</span>
                <span className="stat-icon blue">
                  <MapPin size={17} />
                </span>
              </div>
              <div style={{ fontWeight: 700, fontSize: 16 }}>{data.employee.transport.pickupPoint ?? 'Not set yet'}</div>
              <div className="stat-foot">Set by your transport team</div>
            </div>
            <div className="card stat">
              <div className="stat-top">
                <span className="stat-label">Assistance</span>
                <span className="stat-icon violet">
                  <Accessibility size={17} />
                </span>
              </div>
              <div style={{ fontWeight: 700, fontSize: 16 }}>{data.employee.transport.specialAssistance ?? 'None needed'}</div>
              <div className="stat-foot">Tell your transport team if this changes</div>
            </div>
          </div>

          <Card title="Upcoming rides" icon={<CalendarClock size={16} className="muted" />} pad={false}>
            {data.bookings.length === 0 ? (
              <Empty title="No rides booked">Your upcoming cab bookings will appear here.</Empty>
            ) : (
              <table className="table">
                <tbody>
                  {data.bookings.map((b) => (
                    <tr key={b.id}>
                      <td style={{ fontWeight: 600, width: 160 }}>{dateLabel(b.rideDate)}</td>
                      <td>{b.direction === 'PICKUP' ? 'Home → office' : 'Office → home'}</td>
                      <td className="muted">{shiftLabel(b.shiftCode)}</td>
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
      )}
    </>
  );
}
