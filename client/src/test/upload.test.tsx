import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import UploadForm, { MAX_PDF_BYTES, validatePdf } from '../components/UploadForm';

function pdf(name: string, size: number, type = 'application/pdf'): File {
  const blob = new Blob(['x'], { type });
  const file = new File([blob], name, { type });
  Object.defineProperty(file, 'size', { value: size });
  return file;
}

describe('validatePdf', () => {
  it('accepts a normal PDF', () => {
    expect(validatePdf(pdf('tender.pdf', 1024))).toBeNull();
  });

  it('accepts PDFs by extension when the browser gives no MIME type', () => {
    expect(validatePdf(pdf('scan.pdf', 1024, ''))).toBeNull();
  });

  it('rejects non-PDF files', () => {
    expect(validatePdf(pdf('evil.exe', 1024, 'application/octet-stream'))).toMatch(/only pdf/i);
  });

  it(`rejects files over ${MAX_PDF_BYTES} bytes`, () => {
    expect(validatePdf(pdf('huge.pdf', MAX_PDF_BYTES + 1))).toMatch(/exceeds/);
  });

  it('rejects empty files', () => {
    expect(validatePdf(pdf('empty.pdf', 0))).toMatch(/empty/);
  });
});

describe('UploadForm', () => {
  it('blocks submit without a file and shows server errors', () => {
    const onUpload = vi.fn();
    render(
      <UploadForm label="Tender PDF" pending={false} error="PDF rejected: BAD_MAGIC" onUpload={onUpload} />,
    );
    fireEvent.click(screen.getByRole('button', { name: /upload/i }));
    const alerts = screen.getAllByRole('alert').map((alert) => alert.textContent);
    expect(alerts).toContain('Choose a file first.');
    expect(alerts).toContain('PDF rejected: BAD_MAGIC');
    expect(onUpload).not.toHaveBeenCalled();
  });
});
