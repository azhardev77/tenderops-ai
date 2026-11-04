import { useEffect, useState } from 'react';
import { authHeaders, redirectToLogin } from '../api/client';

/** Fetches an image with the auth header and renders it via an object URL. */
export default function AuthedImage({
  src,
  alt,
  style,
}: {
  src: string;
  alt: string;
  style?: React.CSSProperties;
}) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let current: string | null = null;
    setFailed(false);
    setObjectUrl(null);
    void fetch(src, { headers: authHeaders() })
      .then((response) => {
        if (response.status === 401) {
          redirectToLogin();
          throw new Error('unauthorized');
        }
        if (!response.ok) {
          throw new Error('image failed: ' + response.status);
        }
        return response.blob();
      })
      .then((blob) => {
        if (!cancelled) {
          current = URL.createObjectURL(blob);
          setObjectUrl(current);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true);
        }
      });
    return () => {
      cancelled = true;
      if (current) {
        URL.revokeObjectURL(current);
      }
    };
  }, [src]);

  if (failed) {
    return <p role="alert">Image unavailable.</p>;
  }
  if (!objectUrl) {
    return <p>Loading image…</p>;
  }
  return <img src={objectUrl} alt={alt} style={style} />;
}
