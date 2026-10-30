import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import { Layout } from './components/Layout.jsx';
import { Activity } from './pages/Activity.jsx';
import { Employees } from './pages/Employees.jsx';
import { Login } from './pages/Login.jsx';
import { MyCommute } from './pages/MyCommute.jsx';
import { Nightly } from './pages/Nightly.jsx';
import { Overview } from './pages/Overview.jsx';
import { RunDetail } from './pages/RunDetail.jsx';
import { TestFeed } from './pages/TestFeed.jsx';

export function App() {
  const { user } = useAuth();
  if (!user) return <Login />;

  if (user.role === 'EMPLOYEE') {
    return (
      <Routes>
        <Route element={<Layout />}>
          <Route path="/me" element={<MyCommute />} />
          <Route path="*" element={<Navigate to="/me" replace />} />
        </Route>
      </Routes>
    );
  }

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Overview />} />
        <Route path="/employees" element={<Employees />} />
        <Route path="/nightly" element={<Nightly />} />
        <Route path="/nightly/:runId" element={<RunDetail />} />
        <Route path="/activity" element={<Activity />} />
        <Route path="/activity/:batchId" element={<Activity />} />
        {user.role === 'OPERATOR' && <Route path="/test-feed" element={<TestFeed />} />}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
