import { useState } from 'react';
import type { RegionDto } from '../api/types';
import AuthedImage from './AuthedImage';
import { SourceBadge } from './badges';

const REGION_TYPES = ['OFFICIAL_STAMP', 'HANDWRITTEN_SIGNATURE', 'TABLE_REGION'] as const;

const OVERLAY_COLORS: Record<string, string> = {
  OFFICIAL_STAMP: 'red',
  HANDWRITTEN_SIGNATURE: 'blue',
  TABLE_REGION: 'green',
};

function TableView({ table }: { table: unknown }) {
  if (typeof table !== 'object' || table === null || !('rows' in table)) {
    return <p>No structured table.</p>;
  }
  const rows = (table as { rows: unknown }).rows;
  if (!Array.isArray(rows)) {
    return <p>No structured table.</p>;
  }
  return (
    <table>
      <tbody>
        {rows.map((row, index) => (
          // eslint-disable-next-line react/no-array-index-key
          <tr key={index}>
            {(Array.isArray(row) ? row : [row]).map((cell, cellIndex) => (
              // eslint-disable-next-line react/no-array-index-key
              <td key={cellIndex}>{String(cell ?? '')}</td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Page image with toggleable region overlays. Coordinates are normalized top-left
 * [0,1] boxes, rendered as % overlays. Region panels show extracted text with
 * source badges only, never any judgement about the mark itself.
 */
export default function PageViewer({
  versionId,
  pageNo,
  pageCount,
  regions,
  onPageChange,
  highlightQuote,
}: {
  versionId: number;
  pageNo: number;
  pageCount: number;
  regions: RegionDto[];
  onPageChange: (pageNo: number) => void;
  highlightQuote?: string | null;
}) {
  const [visible, setVisible] = useState<Record<string, boolean>>({
    OFFICIAL_STAMP: true,
    HANDWRITTEN_SIGNATURE: true,
    TABLE_REGION: true,
  });
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const selected = regions.find((region) => region.id === selectedId) ?? null;

  const toggle = (type: string) => {
    setVisible((current) => ({ ...current, [type]: !current[type] }));
  };

  return (
    <div>
      <div>
        <button
          type="button"
          disabled={pageNo <= 1}
          onClick={() => onPageChange(pageNo - 1)}
        >
          Prev
        </button>{' '}
        <span data-testid="page-indicator">
          Page {pageNo} of {pageCount}
        </span>{' '}
        <button
          type="button"
          disabled={pageNo >= pageCount}
          onClick={() => onPageChange(pageNo + 1)}
        >
          Next
        </button>
      </div>
      <fieldset>
        <legend>Overlays</legend>
        {REGION_TYPES.map((type) => (
          <label key={type} style={{ marginRight: '1rem' }}>
            <input
              type="checkbox"
              checked={visible[type] ?? false}
              onChange={() => toggle(type)}
            />{' '}
            {type}
          </label>
        ))}
      </fieldset>
      <div data-testid="page-canvas" style={{ position: 'relative', maxWidth: 640 }}>
        <AuthedImage
          src={`/api/v1/tender-versions/${versionId}/pages/${pageNo}/image`}
          alt={`Tender page ${pageNo}`}
          style={{ width: '100%', border: '1px solid #999' }}
        />
        {regions
          .filter((region) => visible[region.regionType])
          .map((region) => (
            <button
              key={region.id}
              type="button"
              aria-label={`${region.regionType} overlay`}
              data-testid={`overlay-${region.id}`}
              onClick={() => setSelectedId(region.id)}
              style={{
                position: 'absolute',
                left: `${Number(region.bbox.x0) * 100}%`,
                top: `${Number(region.bbox.y0) * 100}%`,
                width: `${(Number(region.bbox.x1) - Number(region.bbox.x0)) * 100}%`,
                height: `${(Number(region.bbox.y1) - Number(region.bbox.y0)) * 100}%`,
                border: `2px solid ${OVERLAY_COLORS[region.regionType] ?? 'black'}`,
                background: 'transparent',
                padding: 0,
                cursor: 'pointer',
              }}
            />
          ))}
      </div>
      {highlightQuote && (
        <blockquote data-testid="source-quote">{highlightQuote}</blockquote>
      )}
      {selected && (
        <section data-testid="region-panel">
          <h4>
            {selected.regionType} <SourceBadge source={selected.source} />
          </h4>
          {selected.contentSource && (
            <p>
              Content source: <SourceBadge source={selected.contentSource} />
            </p>
          )}
          {selected.regionText && <p>{selected.regionText}</p>}
          {selected.regionType === 'TABLE_REGION' && (
            <TableView table={selected.structuredTable} />
          )}
          {selected.cropUrl && (
            <p>
              <AuthedImage
                src={selected.cropUrl}
                alt={`${selected.regionType} crop`}
                style={{ maxWidth: 320, border: '1px solid #999' }}
              />
            </p>
          )}
        </section>
      )}
    </div>
  );
}
