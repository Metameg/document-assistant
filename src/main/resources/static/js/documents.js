import {getJson} from "./api.js";

let catalog = new Map();

async function loadDocuments() {
  const message = document.querySelector("#documents-message");
  const list = document.querySelector("#document-list");
  try {
    const documents = await getJson("/api/demo/documents");
    catalog = new Map(documents.map(item => [item.documentId, item]));
    list.replaceChildren();
    for (const item of documents) {
      const row = document.createElement("li");
      const select = document.createElement("button");
      select.type = "button";
      select.className = "document-select";
      select.textContent = item.sourceFile;
      select.dataset.documentId = item.documentId;
      select.addEventListener("click", () => selectDocument(item));
      row.append(select);
      const details = document.createElement("p");
      details.textContent = `${item.pageCount} pages · Revision ${item.revision?.revision || "unspecified"} · Models: ${item.modelNumbers.join(", ") || "none listed"} · ID: ${item.documentId}`;
      row.append(details);
      const actions = document.createElement("p");
      if (item.pdfAvailable) {
        const open = pdfLink(item.documentId, "Open PDF ↗");
        const download = pdfLink(item.documentId, "Download PDF");
        download.removeAttribute("target");
        download.download = item.sourceFile;
        actions.append(open, document.createTextNode(" · "), download);
      } else {
        actions.textContent = "PDF unavailable";
      }
      row.append(actions);
      list.append(row);
    }
    message.textContent = documents.length ? `${documents.length} documents available.` : "No processed documents were found.";
    if (documents.length) selectDocument(documents.find(item => item.pdfAvailable) || documents[0]);
  } catch (error) {
    message.classList.add("error");
    message.textContent = `Could not load documents: ${error.message}`;
  }
}

function pdfUrl(documentId) {
  return `/api/demo/documents/${encodeURIComponent(documentId)}/pdf`;
}

export function pdfLink(documentId, text) {
  const link = document.createElement("a");
  link.href = pdfUrl(documentId);
  link.target = "_blank";
  link.rel = "noopener";
  link.textContent = text;
  return link;
}

export function selectDocument(item) {
  document.querySelectorAll(".document-select").forEach(button => {
    button.setAttribute("aria-current", String(button.dataset.documentId === item.documentId));
  });
  document.querySelector("#pdf-title").textContent = item.sourceFile;
  const viewer = document.querySelector("#pdf-viewer");
  viewer.hidden = !item.pdfAvailable;
  document.querySelector("#pdf-actions").hidden = !item.pdfAvailable;
  if (!item.pdfAvailable) {
    viewer.removeAttribute("src");
    document.querySelector("#pdf-message").textContent = "The PDF for this document is unavailable.";
    return;
  }
  viewer.title = `Specification PDF: ${item.sourceFile}`;
  viewer.src = pdfUrl(item.documentId);
  const open = document.querySelector("#pdf-open");
  open.href = viewer.src;
  const download = document.querySelector("#pdf-download");
  download.href = viewer.src;
  download.download = item.sourceFile;
  document.querySelector("#pdf-message").textContent = "PDF selected. Use Open PDF if the embedded preview is unavailable.";
}

export function getCatalogDocument(documentId) {
  return catalog.get(documentId);
}

export function initializeDocuments() {
  loadDocuments();
}

