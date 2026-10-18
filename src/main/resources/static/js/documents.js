import {getJson} from "./api.js";
import {currentCorpus, corpusUrl, corpusBusy, requestDocumentRemoval} from "./corpora.js?v=corpus-deletion-5";

let catalog = new Map();

export async function loadDocuments() {
  const corpusId = currentCorpus().id;
  const message = document.querySelector("#documents-message");
  const list = document.querySelector("#document-list");
  try {
    const documents = await getJson(corpusUrl("/documents"))
    ; if (currentCorpus().id !== corpusId) return;
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
      details.textContent = currentCorpus().profile === "research" ? `${item.pageCount} pages · ${item.indexed ? "Indexed" : "Pending processing"}` : `${item.pageCount} pages · Revision ${item.revision?.revision || "unspecified"} · Models: ${item.modelNumbers.join(", ") || "none listed"}`;
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
      if (currentCorpus().profile === "research") {
        const remove = document.createElement("button");
        remove.type = "button"; remove.className = "danger secondary remove-document";
        remove.textContent = "Remove"; remove.setAttribute("aria-label", `Remove ${item.sourceFile}`);
        remove.disabled = corpusBusy();
        remove.onclick = () => requestDocumentRemoval(item);
        actions.append(remove);
      }
      row.append(actions);
      list.append(row);
    }
    message.textContent = documents.length ? `${documents.length} documents available.` : "No documents yet. Upload PDFs to build this corpus.";
    if (documents.length) selectDocument(documents.find(item => item.pdfAvailable) || documents[0]);
    else { document.querySelector("#pdf-viewer").removeAttribute("src"); document.querySelector("#pdf-viewer").hidden = true; document.querySelector("#pdf-actions").hidden = true; document.querySelector("#pdf-title").textContent = "Choose a document"; document.querySelector("#pdf-message").textContent = "Upload a PDF to build this corpus."; }
  } catch (error) {
    message.classList.add("error");
    message.textContent = `Could not load documents: ${error.message}`;
  }
}

function pdfUrl(documentId) {
  return corpusUrl(`/documents/${encodeURIComponent(documentId)}/pdf`);
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
  viewer.title = `Document PDF: ${item.sourceFile}`;
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
  window.addEventListener("corpuschange", loadDocuments);
  window.addEventListener("corpusupdated", loadDocuments);
}

