import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// RTL auto-cleanup is not installed without vitest globals; install it explicitly.
afterEach(() => {
  cleanup();
});
