import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { RegionDto } from '../api/types';

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => 'blob:mock');
  URL.revokeObjectURL = vi.fn();
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      blob: () => Promise.resolve(new Blob(['img'])),
    }),
  );
  return () => vi.unstubAllGlobals();
});
import PageViewer from '../components/PageViewer';
import { ActivityFeed, StageStepper } from '../components/StageStepper';
import type { StreamEvent } from '../api/types';

function region(id: number, type: string): RegionDto {
  return {
    id,
    pageNo: 1,
    regionType: type,
    bbox: { x0: '0.1', y0: '0.1', x1: '0.4', y1: '0.3' },
    confidence: '0.87',
    source: 'FALLBACK_HEURISTIC',
    contentSource: 'PDFBOX_GEOMETRIC',
    regionText: 'extracted text',
    structuredTable: { rows: [['a', 'b']] },
    cropUrl: null,
  };
}

function event(seq: number, type: string, payload: Record<string, unknown>): StreamEvent {
  return { seq, type, stage: null, payload, createdAt: '' };
}

describe('PageViewer overlays', () => {
  it('renders one overlay per region and toggles by type', () => {
    const onPageChange = vi.fn();
    render(
      <PageViewer
        versionId={7}
        pageNo={1}
        pageCount={6}
        regions={[region(1, 'OFFICIAL_STAMP'), region(2, 'TABLE_REGION')]}
        onPageChange={onPageChange}
      />,
    );
    expect(screen.getByTestId('overlay-1')).toBeInTheDocument();
    expect(screen.getByTestId('overlay-2')).toBeInTheDocument();
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 1 of 6');

    const toggle = screen.getByRole('checkbox', { name: /TABLE_REGION/ });
    fireEvent.click(toggle);
    expect(screen.getByTestId('overlay-1')).toBeInTheDocument();
    expect(screen.queryByTestId('overlay-2')).not.toBeInTheDocument();
  });

  it('region panel shows extracted text with the source badge', () => {
    render(
      <PageViewer
        versionId={7}
        pageNo={1}
        pageCount={6}
        regions={[region(2, 'TABLE_REGION')]}
        onPageChange={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('overlay-2'));
    expect(screen.getByTestId('region-panel')).toHaveTextContent('extracted text');
    expect(screen.getByTestId('source-FALLBACK_HEURISTIC')).toBeInTheDocument();
    expect(screen.getByTestId('source-PDFBOX_GEOMETRIC')).toBeInTheDocument();
    expect(screen.getByTestId('region-panel')).toHaveTextContent('a');
  });

  it('page buttons navigate', () => {
    const onPageChange = vi.fn();
    render(
      <PageViewer
        versionId={7}
        pageNo={2}
        pageCount={6}
        regions={[]}
        onPageChange={onPageChange}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(onPageChange).toHaveBeenCalledWith(3);
    fireEvent.click(screen.getByRole('button', { name: 'Prev' }));
    expect(onPageChange).toHaveBeenCalledWith(1);
  });
});

describe('StageStepper and ActivityFeed', () => {
  const events = [
    event(1, 'STAGE_STARTED', { stage: 'VALIDATE' }),
    event(2, 'STAGE_COMPLETED', { stage: 'VALIDATE' }),
    event(3, 'STAGE_STARTED', { stage: 'PARSE' }),
    event(4, 'VERDICT_COMPUTED', { clauseRef: '4.1', verdict: 'PASS' }),
    event(5, 'STAGE_FAILED', { stage: 'RENDER' }),
  ];

  it('marks done/active/failed stages', () => {
    render(<StageStepper events={events} />);
    expect(screen.getByTestId('stage-VALIDATE')).toHaveAttribute('data-state', 'done');
    expect(screen.getByTestId('stage-PARSE')).toHaveAttribute('data-state', 'active');
    expect(screen.getByTestId('stage-RENDER')).toHaveAttribute('data-state', 'failed');
    expect(screen.getByTestId('stage-DECIDE')).toHaveAttribute('data-state', 'pending');
  });

  it('describes verdict and stage events', () => {
    render(<ActivityFeed events={events} />);
    const feed = screen.getByTestId('activity-feed');
    expect(feed).toHaveTextContent('VERDICT_COMPUTED clause 4.1 = PASS');
    expect(feed).toHaveTextContent('STAGE_COMPLETED VALIDATE');
  });

  it('shows an empty feed message', () => {
    render(<ActivityFeed events={[]} />);
    expect(screen.getByText(/no activity yet/i)).toBeInTheDocument();
  });
});
