import { useState } from "react";
import { downloadWordDocument } from "../documentDownload";

export default function DocumentDownloadButton({ title, content, versionId }) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState("");

  async function download() {
    setDownloading(true);
    setError("");
    try {
      await downloadWordDocument(title, content, versionId);
    } catch {
      setError("Couldn’t download the Word document. Please try again.");
    } finally {
      setDownloading(false);
    }
  }

  return (
    <>
      <button type="button" className="workspace-button document-download"
        disabled={downloading} onClick={download}>
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3v12m-4-4 4 4 4-4M5 15v5h14v-5" />
        </svg>
        {downloading ? "Preparing…" : "Download Word"}
      </button>
      {error && <p className="document-download-error" role="alert">{error}</p>}
    </>
  );
}
