import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ProtectedRoute from './components/ProtectedRoute';
import Agent from './pages/Agent';
import Amendments from './pages/Amendments';
import Audit from './pages/Audit';
import Company from './pages/Company';
import Dashboard from './pages/Dashboard';
import Login from './pages/Login';
import Package from './pages/Package';
import Register from './pages/Register';
import Settings from './pages/Settings';
import Tenders from './pages/Tenders';
import Workspace from './pages/Workspace';
import './app.css';

const queryClient = new QueryClient();

function Guarded({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute>
      <Layout>{children}</Layout>
    </ProtectedRoute>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route
          path="/"
          element={
            <Guarded>
              <Dashboard />
            </Guarded>
          }
        />
        <Route
          path="/tenders"
          element={
            <Guarded>
              <Tenders />
            </Guarded>
          }
        />
        <Route
          path="/tenders/:id/workspace"
          element={
            <Guarded>
              <Workspace />
            </Guarded>
          }
        />
        <Route
          path="/tenders/:id/amendments"
          element={
            <Guarded>
              <Amendments />
            </Guarded>
          }
        />
        <Route
          path="/tenders/:id/package"
          element={
            <Guarded>
              <Package />
            </Guarded>
          }
        />
        <Route
          path="/tenders/:id/agent"
          element={
            <Guarded>
              <Agent />
            </Guarded>
          }
        />
        <Route
          path="/company"
          element={
            <Guarded>
              <Company />
            </Guarded>
          }
        />
        <Route
          path="/audit"
          element={
            <Guarded>
              <Audit />
            </Guarded>
          }
        />
        <Route
          path="/settings"
          element={
            <Guarded>
              <Settings />
            </Guarded>
          }
        />
      </Routes>
    </QueryClientProvider>
  );
}
