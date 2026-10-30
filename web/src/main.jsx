import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import { App } from './App.jsx';
import { AuthProvider } from './auth.jsx';
import { ToastProvider } from './components/ui.jsx';
import './styles.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: (count, err) => err?.status >= 500 && count < 2, refetchOnWindowFocus: false, staleTime: 5_000 },
  },
});

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <ToastProvider>
            <App />
          </ToastProvider>
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
