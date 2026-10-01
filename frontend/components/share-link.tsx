"use client";

import { useEffect, useId, useRef, useState } from "react";
import type { ShortUrl } from "@/lib/types";

/** QR generation stays in this browser; no link is sent to a QR service. */
export function ShareLink({ url }: { url: Pick<ShortUrl, "shortUrl" | "shortCode"> }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const [open, setOpen] = useState(false);
  const [image, setImage] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [sharing, setSharing] = useState(false);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setImage(""); setFile(null); setError(""); setMessage("");
    async function generate() {
      try {
        if (!/^https?:\/\//i.test(url.shortUrl)) throw new Error("Invalid short URL");
        const QRCode = await import("qrcode");
        const canvas = document.createElement("canvas");
        await QRCode.toCanvas(canvas, url.shortUrl, { width: 768, margin: 4, errorCorrectionLevel: "M" });
        const png = canvas.toDataURL("image/png");
        const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, "image/png"));
        if (!blob) throw new Error("PNG generation failed");
        if (!cancelled) {
          setImage(png);
          setFile(new File([blob], `shortify-${url.shortCode}.png`, { type: "image/png" }));
        }
      } catch { if (!cancelled) setError("Could not generate the QR code. Close this window and try again."); }
    }
    void generate();
    return () => { cancelled = true; };
  }, [open, url.shortUrl, url.shortCode]);

  function close() {
    dialog.current?.close();
    setOpen(false);
    trigger.current?.focus();
  }

  async function share(asImage: boolean) {
    if (sharing) return;
    setSharing(true); setMessage("");
    try {
      if (asImage && file) {
        if (navigator.canShare?.({ files: [file] })) {
          await navigator.share({ files: [file], title: `Short link: ${url.shortCode}` });
          setMessage("QR code shared.");
        } else {
          setMessage("Image sharing is unavailable in this browser. Download the PNG below and attach it to your message.");
        }
      } else if (navigator.share) {
        await navigator.share({ title: `Short link: ${url.shortCode}`, url: url.shortUrl });
        setMessage("Link shared.");
      } else {
        await navigator.clipboard.writeText(url.shortUrl);
        setMessage("Sharing is unavailable in this browser. Link copied to clipboard.");
      }
    } catch (failure) {
      if (!(failure instanceof Error && failure.name === "AbortError")) {
        setMessage("Sharing was unavailable. Copy the URL below or download the QR code instead.");
      }
    } finally { setSharing(false); }
  }

  return (
    <>
      <button type="button" className="button secondary small" ref={trigger}
        aria-label={`QR & share ${url.shortCode}`} onClick={() => { dialog.current?.showModal(); setOpen(true); }}>
        QR &amp; share
      </button>
      <dialog ref={dialog} className="share-dialog" aria-labelledby={titleId}
        onCancel={event => { event.preventDefault(); close(); }} onClose={() => setOpen(false)}>
        <div className="panel-heading">
          <h2 id={titleId}>Share your short link</h2>
          <button type="button" className="button secondary small" onClick={close}>Close</button>
        </div>
        <p>Scan to open the short URL. The link’s schedule, expiration, and click cap still apply.</p>
        <div className="qr-preview" aria-busy={!image && !error}>
          {image ? <img src={image} width={256} height={256} alt={`QR code for ${url.shortUrl}`} />
            : <p role="status">{error || "Generating QR code…"}</p>}
        </div>
        <label className="field">Short URL
          <input value={url.shortUrl} readOnly onFocus={event => event.target.select()} />
        </label>
        <div className="share-actions">
          <button type="button" className="button secondary" disabled={sharing} onClick={() => void share(false)}>Share link</button>
          <button type="button" className="button secondary" disabled={!file || sharing} onClick={() => void share(true)}>Share QR image</button>
          {image && <a className="button" href={image} download={`shortify-${url.shortCode}.png`}>Download PNG</a>}
        </div>
        <p role="status">{message}</p>
      </dialog>
    </>
  );
}
