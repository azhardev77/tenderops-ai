import { useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useDashboardStats } from '../api/queries';
import type { StreamEvent } from '../api/types';
import { ErrorState, Loading } from '../components/states';
import { ConnectionBadge } from '../components/badges';
import { useEventStream } from '../hooks/useEventStream';

export default function Dashboard() {
  const queryClient = useQueryClient();
  const stats = useDashboardStats();
  const { state } = useEventStream({
    url: '/api/v1/dashboard/events',
    pollUrl: '/api/v1/dashboard/events',
    onEvent: (event: StreamEvent) => {
      if (event.type === 'JOB_COMPLETED' || event.type === 'DECISION_UPDATED') {
        void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
        void queryClient.invalidateQueries({ queryKey: ['tenders'] });
      }
    },
  });

  if (stats.isLoading) {
    return <Loading label="dashboard" />;
  }
  if (stats.isError || !stats.data) {
    return (
      <ErrorState
        message={(stats.error as Error)?.message ?? 'Dashboard failed to load.'}
        onRetry={() => void stats.refetch()}
      />
    );
  }
  const data = stats.data;
  return (
    <main>
      <h1>Dashboard</h1>
      <ConnectionBadge state={state} />
      <ul>
        <li>Total tenders: {data.totalTenders}</li>
        <li>Processing: {data.processingTenders}</li>
        <li>Completed: {data.completedTenders}</li>
        <li>
          Decisions: BID {data.decisionBreakdown['BID'] ?? 0}, CONDITIONAL_BID{' '}
          {data.decisionBreakdown['CONDITIONAL_BID'] ?? 0}, NO_BID{' '}
          {data.decisionBreakdown['NO_BID'] ?? 0}, INSUFFICIENT_DATA{' '}
          {data.decisionBreakdown['INSUFFICIENT_DATA'] ?? 0}
        </li>
        <li>Pending reviews: {data.pendingReviews}</li>
        <li>Critical issues: {data.criticalIssues}</li>
        <li>
          Performance: avg {data.performance.avgMsPerPage ?? '—'} ms/page, last run{' '}
          {data.performance.lastRunMs ?? '—'} ms
        </li>
      </ul>
      <h2>Upcoming deadlines</h2>
      {data.upcomingDeadlines.length === 0 ? (
        <p>No upcoming deadlines.</p>
      ) : (
        <ul>
          {data.upcomingDeadlines.map((deadline) => (
            <li key={`${deadline.tenderId}-${deadline.deadline}`}>
              <Link to={`/tenders/${deadline.tenderId}/workspace`}>
                {deadline.reference ?? `Tender ${deadline.tenderId}`}
              </Link>{' '}
              — {deadline.deadline}
            </li>
          ))}
        </ul>
      )}
      <h2>Time saved</h2>
      {data.timeSaved ? (
        <ul>
          <li>Saved: {data.timeSaved.percent}%</li>
          <li>Manual baseline: {data.timeSaved.manualMinutes ?? '—'} min</li>
          <li>Assisted: {data.timeSaved.assistedMinutes ?? '—'} min</li>
          <li>
            AI mode: {data.timeSaved.aiMode}{' '}
            {data.timeSaved.representative ? '(representative)' : '(not representative)'}
          </li>
        </ul>
      ) : (
        <p>No completed runs yet.</p>
      )}
    </main>
  );
}
