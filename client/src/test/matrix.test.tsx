import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MatrixRow } from '../api/types';
import MatrixTable from '../components/MatrixTable';
import { DecisionBanner, VerdictBadge } from '../components/badges';

function row(clauseRef: string, verdict: string, id = 1): MatrixRow {
  return {
    requirement: {
      id,
      clauseRef,
      mandatory: true,
      metric: 'NET_WORTH',
      operator: '>=',
      amountText: 'Rs 50 Cr',
      sourcePage: 3,
      originalQuote: 'quote text',
      confidence: '0.92',
    },
    verdict,
    reasonCode: 'THRESHOLD_MET',
    calculationTrace: { verdict, formula: 'netWorth >= 500000000.0000' },
    copied: false,
  };
}

describe('MatrixTable verdict states', () => {
  it('renders all four verdict states distinctly', () => {
    render(
      <MatrixTable
        rows={[
          row('4.1', 'PASS', 1),
          row('5.1', 'FAIL', 2),
          row('6.2', 'UNKNOWN', 3),
          row('6.1', 'NEEDS_REVIEW', 4),
        ]}
      />,
    );
    expect(screen.getByTestId('verdict-PASS')).toHaveTextContent('PASS');
    expect(screen.getByTestId('verdict-FAIL')).toHaveTextContent('FAIL');
    expect(screen.getByTestId('verdict-UNKNOWN')).toHaveTextContent('UNKNOWN');
    expect(screen.getByTestId('verdict-NEEDS_REVIEW')).toHaveTextContent('NEEDS_REVIEW');
  });

  it('shows an empty state without rows', () => {
    render(<MatrixTable rows={[]} />);
    expect(screen.getByText(/no requirements evaluated/i)).toBeInTheDocument();
  });
});

describe('Trace drawer money strings', () => {
  it('renders the trace JSON with exact money strings', () => {
    render(<MatrixTable rows={[row('4.2', 'PASS')]} />);
    const toggle = screen.getByRole('button', { name: /show trace/i });
    fireEvent.click(toggle);
    expect(screen.getByTestId('trace-json')).toHaveTextContent('500000000.0000');
  });
});

describe('Page chip and row selection', () => {
  it('page chip jumps to the source page', () => {
    const onPageChip = vi.fn();
    render(<MatrixTable rows={[row('4.1', 'PASS')]} onPageChip={onPageChip} />);
    fireEvent.click(screen.getByRole('button', { name: 'p3' }));
    expect(onPageChip).toHaveBeenCalledWith(3);
  });

  it('clause button selects the row', () => {
    const onSelectRow = vi.fn();
    render(
      <MatrixTable rows={[row('4.1', 'PASS')]} onSelectRow={onSelectRow} selectedId={null} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '4.1' }));
    expect(onSelectRow).toHaveBeenCalledTimes(1);
  });
});

describe('DecisionBanner', () => {
  it.each(['BID', 'CONDITIONAL_BID', 'NO_BID', 'INSUFFICIENT_DATA'])(
    'renders the %s decision',
    (decision) => {
      render(<DecisionBanner decision={decision} />);
      expect(screen.getByTestId(`decision-${decision}`)).toHaveTextContent(decision);
    },
  );
});

describe('VerdictBadge', () => {
  it('falls back gracefully for unknown verdicts', () => {
    render(<VerdictBadge verdict="SOMETHING_NEW" />);
    expect(screen.getByTestId('verdict-SOMETHING_NEW')).toHaveTextContent('SOMETHING_NEW');
  });
});
