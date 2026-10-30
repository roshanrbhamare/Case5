import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { api, setToken, setUnauthorizedHandler } from './api.js';

const AuthContext = createContext(null);
const STORAGE_KEY = 'rostersync.session';

function readSession() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const queryClient = useQueryClient();
  const [session, setSession] = useState(() => {
    const s = readSession();
    if (s?.token) setToken(s.token);
    return s;
  });

  const logout = useCallback(() => {
    setToken(null);
    setSession(null);
    queryClient.clear();
    try {
      localStorage.removeItem(STORAGE_KEY);
    } catch {
      // storage unavailable: nothing to clear
    }
  }, [queryClient]);

  useEffect(() => setUnauthorizedHandler(logout), [logout]);

  const start = useCallback((result) => {
    setToken(result.token);
    setSession(result);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(result));
    } catch {
      // private mode: session lasts for this tab only
    }
    return result.user;
  }, []);

  // Staff (operators, transport admins) sign in with a password.
  const login = useCallback(async (email, password) => start(await api('/v1/auth/login', { method: 'POST', body: { email, password } })), [start]);

  // Employees sign in with a one-time code sent to the email HR gave us.
  const signInWithCode = useCallback(
    async (email, code) => start(await api('/v1/auth/sign-in-code/verify', { method: 'POST', body: { email, code } })),
    [start],
  );

  const value = useMemo(() => ({ user: session?.user ?? null, login, signInWithCode, logout }), [session, login, signInWithCode, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  return useContext(AuthContext);
}

export const ROLE_LABEL = {
  OPERATOR: 'Sync operator',
  ADMIN: 'Transport admin',
  EMPLOYEE: 'Employee',
};
