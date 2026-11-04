import { useState } from 'react';

export const MAX_PDF_BYTES = 26214400;

export function validatePdf(file: File): string | null {
  const isPdf =
    file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
  if (!isPdf) {
    return 'Only PDF files are accepted.';
  }
  if (file.size > MAX_PDF_BYTES) {
    return `File exceeds the ${MAX_PDF_BYTES}-byte limit.`;
  }
  if (file.size === 0) {
    return 'File is empty.';
  }
  return null;
}

export default function UploadForm({
  label,
  pending,
  error,
  onUpload,
}: {
  label: string;
  pending: boolean;
  error: string | null;
  onUpload: (file: File) => void;
}) {
  const [validation, setValidation] = useState<string | null>(null);
  const [fileName, setFileName] = useState<string>('');

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        const input = event.currentTarget.querySelector('input[type="file"]');
        const file = input && (input as HTMLInputElement).files?.[0];
        if (!file) {
          setValidation('Choose a file first.');
          return;
        }
        const problem = validatePdf(file);
        setValidation(problem);
        if (!problem) {
          onUpload(file);
        }
      }}
    >
      <label>
        {label}
        <input
          type="file"
          accept="application/pdf,.pdf"
          onChange={(event) => {
            const file = event.target.files?.[0] ?? null;
            setFileName(file ? file.name : '');
            setValidation(file ? validatePdf(file) : null);
          }}
        />
      </label>
      {fileName && <p>Selected: {fileName}</p>}
      {validation && <p role="alert">{validation}</p>}
      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={pending}>
        {pending ? 'Uploading…' : 'Upload'}
      </button>
    </form>
  );
}
