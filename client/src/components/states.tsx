export function Loading({ label }: { label: string }) {
  return (
    <p role="status" aria-live="polite">
      Loading {label}…
    </p>
  );
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div>
      <p role="alert">{message}</p>
      {onRetry && (
        <button type="button" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}

export function Empty({ message }: { message: string }) {
  return <p>{message}</p>;
}
