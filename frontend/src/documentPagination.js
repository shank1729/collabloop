import { PAGE_BREAK } from "./documentFormat";

function safeBoundary(text, offset) {
  // Keep both UTF-16 units of an emoji together.
  const unit = text.charCodeAt(offset);
  return unit >= 0xdc00 && unit <= 0xdfff ? offset - 1 : offset;
}

function fittingLength(text, fits) {
  let low = 0;
  let high = Math.min(256, text.length);
  while (fits(text.slice(0, high))) {
    if (high === text.length) return high;
    low = high;
    high = Math.min(high * 2, text.length);
  }
  while (low + 1 < high) {
    const mid = Math.floor((low + high) / 2);
    if (fits(text.slice(0, mid))) low = mid;
    else high = mid;
  }

  const end = safeBoundary(text, low) || (text.codePointAt(0) > 0xffff ? 2 : 1);
  if (text[end] === "\n") return end + 1;
  if (/\s/.test(text[end - 1]) || /\s/.test(text[end])) return end;
  // Move a partially fitting word to the next sheet. Split very long words if needed.
  const word = text.slice(0, end).match(/\S+$/);
  return word?.index || end;
}

export function paginateDocument(text, fits) {
  const pages = [];
  let sectionStart = 0;
  for (const section of text.split(PAGE_BREAK)) {
    let remaining = section;
    let start = sectionStart;
    do {
      const length = remaining ? fittingLength(remaining, fits) : 0;
      const end = start + length;
      pages.push({ start, end, manualBreak: start === sectionStart && sectionStart > 0,
        text: remaining.slice(0, length) });
      if (length === remaining.length && remaining.endsWith("\n") && !fits(remaining)) {
        pages.push({ start: end, end, manualBreak: false, text: "" });
      }
      remaining = remaining.slice(length);
      start = end;
    } while (remaining);
    sectionStart += section.length + 1;
  }
  return pages;
}
