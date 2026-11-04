import { describe, expect, it } from 'vitest';

// Shipped sources as raw text (Vite glob; no node builtins needed).
const modules = import.meta.glob('../**/*.{ts,tsx}', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/**
 * TC-VIS-09 (client half): no UI text may claim stamp/signature validity or show
 * win probability. Scans shipped sources only (src/test excluded: tests name the
 * banned words to ban them).
 */
describe('banned UI text', () => {
  const shipped = Object.entries(modules).filter(([path]) => !path.includes('/test/'));

  it('scans a non-empty source set', () => {
    expect(shipped.length).toBeGreaterThan(10);
  });

  it('never mentions probability or chance of winning', () => {
    const hits = shipped
      .filter(([, text]) =>
        /probability|chance of winning|win chance|win-chance/i.test(text),
      )
      .map(([path]) => path);
    expect(hits).toEqual([]);
  });

  it('never claims stamp/signature validity', () => {
    const forward = /(stamp|signature).{0,30}(valid|authentic|genuine|verified)/i;
    const backward = /(valid|authentic|genuine|verified).{0,30}(stamp|signature)/i;
    const hits = shipped
      .filter(([, text]) => forward.test(text) || backward.test(text))
      .map(([path]) => path);
    expect(hits).toEqual([]);
  });
});
