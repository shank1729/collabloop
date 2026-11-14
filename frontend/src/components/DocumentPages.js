import { useLayoutEffect, useRef, useState } from "react";
import { PAGE_BREAK } from "../documentFormat";
import { paginateDocument } from "../documentPagination";
import { mapTextOffset, mergeDocumentText } from "../documentSync";

export default function DocumentPages({ content, onChange, readOnly = false }) {
  const text = content.replace(/\r\n?/g, "\n");
  const [pages, setPages] = useState(() => paginateDocument(text, () => true));
  const [size, setSize] = useState("");
  const [composition, setComposition] = useState(null);
  const ruler = useRef(null);
  const fields = useRef([]);
  const pendingFocus = useRef(null);
  const lastFocusedPage = useRef(null);
  const renderedPages = useRef(pages);
  const renderedText = useRef(text);
  const preview = !onChange;
  const firstPagePlaceholder = readOnly ? "Viewer access is read-only." : "Start writing here…";

  useLayoutEffect(() => {
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => {
      setSize(entry.contentRect.width + "x" + entry.contentRect.height);
    });
    observer.observe(ruler.current);
    return () => observer.disconnect();
  }, []);

  useLayoutEffect(() => {
    if (composition) return;
    const currentIndex = lastFocusedPage.current;
    const field = fields.current[currentIndex];
    const previousPage = renderedPages.current[currentIndex];
    if (!pendingFocus.current && field && field === document.activeElement && previousPage) {
      pendingFocus.current = {
        start: mapTextOffset(renderedText.current, text, previousPage.start + field.selectionStart),
        end: mapTextOffset(renderedText.current, text, previousPage.start + field.selectionEnd),
        preferNext: field.selectionStart === 0,
      };
    }
    const measurement = ruler.current;
    setPages(paginateDocument(text, (candidate) => {
      if (!measurement.clientHeight) return true;
      measurement.value = candidate;
      return measurement.scrollHeight <= measurement.clientHeight;
    }));
    measurement.value = "";
    renderedText.current = text;
  }, [text, size, composition]);

  useLayoutEffect(() => {
    renderedPages.current = pages;
    const target = pendingFocus.current;
    if (!target) return;
    pendingFocus.current = null;
    const start = Math.min(target.start, pages[pages.length - 1].end);
    const index = pages.findIndex((page, index) =>
      start >= page.start && start <= page.end &&
      !(target.preferNext && start === page.end && pages[index + 1]?.start === start));
    const page = pages[index];
    const field = fields.current[index];
    if (!field || !page) return;
    if (field !== document.activeElement) field.focus();
    field.setSelectionRange(start - page.start, Math.min(target.end, page.end) - page.start);
  }, [pages]);

  function replaceText(start, end, replacement, caret, preferNext = true) {
    const nextText = text.slice(0, start) + replacement + text.slice(end);
    if (nextText === text) return;
    pendingFocus.current = { start: caret, end: caret, preferNext };
    onChange(nextText);
  }

  function insertBreak(page, index) {
    const offset = lastFocusedPage.current === index
      ? fields.current[index]?.selectionStart ?? page.text.length
      : page.text.length;
    const position = page.start + offset;
    replaceText(position, position, PAGE_BREAK, position + 1);
  }

  function editPage(page, field) {
    replaceText(page.start, page.end, field.value, page.start + field.selectionStart);
  }

  function handleBoundaryKey(event, page, index) {
    if (readOnly || composition || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    const { selectionStart, selectionEnd } = event.currentTarget;
    if (selectionStart !== selectionEnd) return;
    const previous = selectionStart === 0 && pages[index - 1];
    const next = selectionStart === page.text.length && pages[index + 1];
    if (previous && event.key === "Backspace") {
      event.preventDefault();
      const length = text.codePointAt(page.start - 2) > 0xffff ? 2 : 1;
      replaceText(page.start - length, page.start, "", page.start - length, false);
    } else if (next && event.key === "Delete") {
      event.preventDefault();
      const length = text.codePointAt(page.end) > 0xffff ? 2 : 1;
      replaceText(page.end, page.end + length, "", page.end, false);
    } else if ((previous && event.key === "ArrowLeft") || (next && event.key === "ArrowRight")) {
      event.preventDefault();
      const left = event.key === "ArrowLeft";
      const target = fields.current[left ? index - 1 : index + 1];
      const offset = left ? previous.text.length : 0;
      target.focus();
      target.setSelectionRange(offset, offset);
    }
  }

  return (
    <div className="document-pages">
      {pages.map((page, index) => {
        const label = preview ? "Saved document content" : "Document content";
        const pageLabel = index === 0 ? label : label + ", page " + (index + 1);
        return (
          <section className="document-paper" key={index} aria-label={"Page " + (index + 1)}>
            <div className="document-paper-header">
              <span>Page {index + 1} of {pages.length}</span>
              {!preview && !readOnly && (
                <div className="document-page-actions">
                  {page.manualBreak && (
                    <button type="button" onClick={() => replaceText(page.start - 1, page.start, "", page.start - 1, false)}>
                      Remove page break
                    </button>
                  )}
                  <button type="button" onClick={() => insertBreak(page, index)}>Insert page break</button>
                </div>
              )}
            </div>
            <div className="document-page-sheet">
              {index === 0 && (
                <textarea ref={ruler} className="editor-textarea document-page-ruler" tabIndex={-1}
                  aria-hidden="true" readOnly />
              )}
              {preview ? (
                <pre className="version-preview-content" tabIndex={0}
                  aria-label={pageLabel}>{page.text}</pre>
              ) : (
                <textarea
                  ref={(field) => { fields.current[index] = field; }}
                  className="editor-textarea"
                  aria-label={pageLabel}
                  value={composition?.index === index ? composition.value : page.text}
                  onFocus={() => { lastFocusedPage.current = index; }}
                  onKeyDown={(event) => handleBoundaryKey(event, page, index)}
                  onCompositionStart={(event) => setComposition({ index, value: event.currentTarget.value,
                    base: text, start: page.start, end: page.end })}
                  onCompositionEnd={(event) => {
                    const draft = composition;
                    setComposition(null);
                    if (!draft) return;
                    const local = draft.base.slice(0, draft.start) + event.currentTarget.value + draft.base.slice(draft.end);
                    const merged = mergeDocumentText(draft.base, local, text);
                    const caret = mapTextOffset(local, merged, draft.start + event.currentTarget.selectionStart);
                    pendingFocus.current = { start: caret, end: caret, preferNext: true };
                    onChange(merged);
                  }}
                  onChange={(event) => {
                    if (composition) setComposition({ ...composition, value: event.currentTarget.value });
                    else editPage(page, event.currentTarget);
                  }}
                  disabled={readOnly}
                  placeholder={index === 0 ? firstPagePlaceholder : undefined}
                />
              )}
            </div>
          </section>
        );
      })}
    </div>
  );
}
