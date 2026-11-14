import { PAGE_BREAK } from "./documentFormat.js";

export async function createWordDocument(title, content) {
  const { Document, HeadingLevel, Packer, Paragraph, Tab, TextRun } = await import("docx");
  const paragraphs = content.split(PAGE_BREAK).flatMap((page, pageIndex) =>
    page.split(/\r\n|\n|\r/).map((line, lineIndex) => new Paragraph({
      pageBreakBefore: pageIndex > 0 && lineIndex === 0,
      spacing: { after: 0 },
      children: [new TextRun({
        children: line.split("\t").flatMap((part, index) => index ? [new Tab(), part] : [part]),
      })],
    }))
  );
  return Packer.toBlob(new Document({
    title,
    sections: [{ children: [new Paragraph({ text: title, heading: HeadingLevel.TITLE }), ...paragraphs] }],
  }));
}

export async function downloadWordDocument(title, content, versionId) {
  const name = title.trim() || "Untitled document";
  const filename = name.replace(/[<>:"/\\|?*]/g, "-").slice(0, 110).replace(/[.\s]+$/, "") || "Untitled document";
  const blob = await createWordDocument(name, content);
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename + (versionId == null ? "" : " - snapshot " + versionId) + ".docx";
  document.body.append(link);
  try {
    link.click();
  } finally {
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}
