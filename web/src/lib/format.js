const nf = new Intl.NumberFormat('en-IN');

export const num = (n) => (n === null || n === undefined ? '—' : nf.format(n));
export const pct = (ratio, digits = 0) => (ratio === null || ratio === undefined ? '—' : `${(ratio * 100).toFixed(digits)}%`);

export function ago(value) {
  if (!value) return '—';
  const seconds = Math.round((Date.now() - new Date(value).getTime()) / 1000);
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} hr ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? 'yesterday' : `${days} days ago`;
}

export function dateLabel(value) {
  if (!value) return '—';
  const d = new Date(`${value}T00:00:00`);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.round((d - today) / 86_400_000);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  return d.toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
}

export const dateTime = (value) =>
  value ? new Date(value).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' }) : '—';

export const initials = (name = '') =>
  name
    .replace(/\./g, '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0].toUpperCase())
    .join('') || '?';

export function shiftLabel(code) {
  if (!code) return 'No shift';
  if (code === 'NIGHT') return 'Night shift';
  const m = /^S(\d{2})(\d{2})$/.exec(code);
  if (!m) return code;
  const hour = Number(m[1]);
  const suffix = hour >= 12 ? 'pm' : 'am';
  const h12 = hour % 12 || 12;
  return `${h12}${m[2] !== '00' ? `:${m[2]}` : ''} ${suffix} shift`;
}

export function greeting() {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

export function duration(seconds) {
  if (!seconds) return '0s';
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const m = Math.floor(seconds / 60);
  return m < 60 ? `${m}m ${Math.round(seconds % 60)}s` : `${Math.floor(m / 60)}h ${m % 60}m`;
}

/** Plain-language explanations of error codes, for the error report. */
export const ERROR_TEXT = {
  INVALID_PHONE: "Phone number isn't valid",
  MISSING_PHONE: 'Phone number is missing',
  INVALID_EMAIL: "Email address isn't valid",
  MISSING_EMAIL: 'Email address is missing',
  MISSING_FULL_NAME: 'Name is missing',
  INVALID_FULL_NAME: "Name isn't valid",
  UNKNOWN_OFFICE_CODE: "Office code doesn't match any office",
  INVALID_OFFICE_CODE: "Office code isn't in the expected format",
  MISSING_OFFICE_CODE: 'Office code is missing',
  INVALID_SHIFT_CODE: "Shift code isn't in the expected format",
  INVALID_MANAGER_ID: "Manager id isn't valid",
  INVALID_EMPLOYEE_ID: "Employee id isn't valid",
  MISSING_EMPLOYEE_ID: 'Employee id is missing',
  INVALID_VERSION: 'Version must be a positive whole number',
  MISSING_VERSION: 'Version is missing',
  INVALID_EVENT_TYPE: 'Event type must be CREATE, UPDATE or DEACTIVATE',
  MISSING_EVENT_TYPE: 'Event type is missing',
  MISSING_DATA: 'Employee details are missing',
  INVALID_RECORD: 'The record is not a JSON object',
  DUPLICATE_IN_FILE: 'Appears more than once in the file (newest version kept)',
  PROCESSING_FAILED: 'Could not be saved; safe to resend',
};

export const errorText = (code) => ERROR_TEXT[code] ?? code.replace(/_/g, ' ').toLowerCase();
