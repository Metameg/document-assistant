import {getCatalogDocument, pdfLink, selectDocument} from "./documents.js";

let queryBusy = false;

// Deliberately render a limited Markdown subset with DOM nodes, never model HTML.
// Supports paragraphs, headings, lists, quotes, fenced code, tables and inline formatting.
function inlineMarkdown(parent, text, evidence = []) {
  const validSources = new Set(evidence.map(item => item.sourceNumber));
  const pattern = /(\[[0-9]+\]|`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|\[[^\]\n]+\]\([^\s)]+\))/g;
  let start = 0;
  for (const match of text.matchAll(pattern)) {
    parent.append(document.createTextNode(text.slice(start, match.index)));
    const token = match[0];
    if (/^\[\d+\]$/.test(token) && validSources.has(Number(token.slice(1, -1)))) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "citation";
      button.textContent = token;
      button.setAttribute("aria-label", `Inspect source ${token}`);
      button.addEventListener("click", () => openSources(evidence, Number(token.slice(1, -1))));
      parent.append(button);
    } else if (token.startsWith("`")) {
      const code = document.createElement("code");
      code.textContent = token.slice(1, -1);
      parent.append(code);
    } else if (token.startsWith("**") || token.startsWith("*")) {
      const strong = token.startsWith("**");
      const node = document.createElement(strong ? "strong" : "em");
      inlineMarkdown(node, token.slice(strong ? 2 : 1, strong ? -2 : -1), evidence);
      parent.append(node);
    } else if (token.includes("](")) {
      const linkMatch = token.match(/^\[([^\]]+)\]\(([^)]+)\)$/);
      let url;
      try { url = new URL(linkMatch[2]); } catch { url = null; }
      if (url && ["https:", "http:"].includes(url.protocol)) {
        const link = document.createElement("a");
        link.href = url.href;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = linkMatch[1];
        parent.append(link);
      } else parent.append(document.createTextNode(token));
    } else parent.append(document.createTextNode(token));
    start = match.index + token.length;
  }
  parent.append(document.createTextNode(text.slice(start)));
}

function renderMarkdown(container, text, evidence) {
  const lines = String(text ?? "").replace(/\r\n?/g, "\n").split("\n");
  const isTableRule = line => /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/.test(line);
  const cells = line => line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map(cell => cell.trim());
  for (let i = 0; i < lines.length;) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    if (/^\s*```/.test(line)) {
      const pre = document.createElement("pre");
      const code = document.createElement("code");
      const block = [];
      i++;
      while (i < lines.length && !/^\s*```/.test(lines[i])) block.push(lines[i++]);
      if (i < lines.length) i++;
      code.textContent = block.join("\n");
      pre.append(code);
      container.append(pre);
      continue;
    }
    if (i + 1 < lines.length && line.includes("|") && isTableRule(lines[i + 1])) {
      const wrap = document.createElement("div"); wrap.className = "table-wrap";
      const table = document.createElement("table");
      const head = document.createElement("thead");
      const row = document.createElement("tr");
      for (const value of cells(line)) { const th = document.createElement("th"); th.scope = "col"; inlineMarkdown(th, value, evidence); row.append(th); }
      head.append(row); table.append(head);
      const body = document.createElement("tbody"); i += 2;
      while (i < lines.length && lines[i].trim() && lines[i].includes("|")) {
        const tr = document.createElement("tr");
        for (const value of cells(lines[i++])) { const td = document.createElement("td"); inlineMarkdown(td, value, evidence); tr.append(td); }
        body.append(tr);
      }
      table.append(body); wrap.append(table); container.append(wrap); continue;
    }
    const heading = line.match(/^(#{1,6})\s+(.+)$/);
    if (heading) { const node = document.createElement(`h${Math.min(heading[1].length + 2, 6)}`); inlineMarkdown(node, heading[2], evidence); container.append(node); i++; continue; }
    const listMatch = line.match(/^\s*(?:([-*+])|\d+[.)])\s+(.+)$/);
    if (listMatch) {
      const ordered = !listMatch[1];
      const list = document.createElement(ordered ? "ol" : "ul");
      if (ordered) list.start = Number(line.trim().match(/^\d+/)[0]);
      while (i < lines.length) {
        const item = lines[i].match(/^\s*(?:([-*+])|\d+[.)])\s+(.+)$/);
        if (!item || Boolean(!item[1]) !== ordered) break;
        const li = document.createElement("li"); inlineMarkdown(li, item[2], evidence); list.append(li); i++;
      }
      container.append(list); continue;
    }
    if (/^>\s?/.test(line)) {
      const quote = document.createElement("blockquote");
      const block = [];
      while (i < lines.length && /^>\s?/.test(lines[i])) block.push(lines[i++].replace(/^>\s?/, ""));
      inlineMarkdown(quote, block.join(" "), evidence); container.append(quote); continue;
    }
    const paragraph = document.createElement("p");
    inlineMarkdown(paragraph, line, evidence); i++;
    while (i < lines.length && lines[i].trim() && !/^(\s*```|#{1,6}\s|\s*[-*+]\s|\s*\d+[.)]\s|>)/.test(lines[i]) && !(i + 1 < lines.length && isTableRule(lines[i + 1]))) {
      paragraph.append(document.createElement("br")); inlineMarkdown(paragraph, lines[i++], evidence);
    }
    container.append(paragraph);
  }
}

const sourcesDialog = document.querySelector("#sources-dialog");
let activeQuestion = "";
function openSources(evidence, sourceNumber = null, question = activeQuestion) {
  const container = document.querySelector("#retrieval-results");
  container.replaceChildren();
  const groups = new Map();
  for (const item of evidence) {
    const id = item.candidate.documentId;
    if (!groups.has(id)) groups.set(id, []);
    groups.get(id).push(item);
  }
  document.querySelector("#sources-title").textContent = `Sources · ${groups.size} PDF${groups.size === 1 ? "" : "s"}`;
  document.querySelector("#sources-question").textContent = question;
  if (!evidence.length) {
    const empty = document.createElement("p"); empty.textContent = "No supporting excerpts were returned."; container.append(empty);
  }
  for (const [id, items] of groups) {
    const card = document.createElement("article"); card.className = "source-document";
    const title = document.createElement("h3"); title.textContent = items[0].candidate.sourceFile || id; card.append(title);
    const catalogDocument = getCatalogDocument(id);
    if (catalogDocument?.pdfAvailable) {
      card.append(pdfLink(id, "Open PDF ↗"));
      const inspect = window.document.createElement("button"); inspect.type = "button"; inspect.className = "secondary"; inspect.textContent = "Inspect in Corpus";
      inspect.addEventListener("click", () => { sourcesDialog.close(); activateTab("tab-corpus", true); selectDocument(catalogDocument); });
      const actions = window.document.createElement("div"); actions.className = "button-group"; actions.append(inspect); card.append(actions);
    } else {
      const unavailable = window.document.createElement("p"); unavailable.className = "subtle";
      unavailable.textContent = catalogDocument ? "PDF unavailable in the catalog." : "PDF availability could not be verified in the catalog."; card.append(unavailable);
    }
    for (const item of items) {
      const details = window.document.createElement("details"); details.className = "source-excerpt"; details.id = `source-${item.sourceNumber}`; details.tabIndex = -1; details.open = item.sourceNumber === sourceNumber;
      const summary = window.document.createElement("summary");
      summary.textContent = `[${item.sourceNumber}] ${item.candidate.section || "Document excerpt"}`;
      if (typeof item.candidate.rrfScore === "number" && Number.isFinite(item.candidate.rrfScore)) {
        const score = window.document.createElement("span"); score.className = "rrf"; score.textContent = ` · RRF ${item.candidate.rrfScore.toFixed(5)}`; summary.append(score);
      }
      const text = window.document.createElement("pre"); text.className = "source-text"; text.textContent = item.candidate.text;
      details.append(summary, text); card.append(details);
    }
    container.append(card);
  }
  if (!sourcesDialog.open) sourcesDialog.showModal();
  if (sourceNumber !== null) requestAnimationFrame(() => {
    const target = window.document.getElementById(`source-${sourceNumber}`);
    target?.focus(); target?.scrollIntoView({block: "nearest"});
  });
}

document.querySelector("#sources-close").addEventListener("click", () => sourcesDialog.close());
sourcesDialog.addEventListener("click", event => {
  if (event.target !== sourcesDialog) return;
  const rect = sourcesDialog.getBoundingClientRect();
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) sourcesDialog.close();
});

const tabs = [...document.querySelectorAll('[role="tab"]')];
function activateTab(id, focus = false) {
  for (const tab of tabs) {
    const selected = tab.id === id;
    tab.setAttribute("aria-selected", String(selected)); tab.tabIndex = selected ? 0 : -1;
    document.getElementById(tab.getAttribute("aria-controls")).hidden = !selected;
    if (selected && focus) tab.focus();
  }
}
tabs.forEach((tab, index) => {
  tab.addEventListener("click", () => activateTab(tab.id));
  tab.addEventListener("keydown", event => {
    let next;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    if (event.key === "ArrowLeft") next = (index + tabs.length - 1) % tabs.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = tabs.length - 1;
    if (next !== undefined) { event.preventDefault(); activateTab(tabs[next].id, true); }
  });
});

function setQueryBusy(busy) {
  queryBusy = busy;
  for (const id of ["answer-submit", "retrieval-submit", "retrieval-query"]) document.getElementById(id).disabled = busy;
  document.querySelector("#conversation").setAttribute("aria-busy", String(busy));
  document.querySelector("#retrieval-message").classList.toggle("busy", busy);
}

function appendMessage(kind, text) {
  document.querySelector("#chat-empty").hidden = true;
  const article = document.createElement("article"); article.className = `message ${kind}-message`;
  const label = document.createElement("div"); label.className = "message-label"; label.textContent = kind === "user" ? "You" : "Document Assistant";
  const body = document.createElement("div"); body.className = kind === "assistant" ? "markdown" : ""; body.textContent = text;
  article.append(label, body); document.querySelector("#conversation").append(article);
  article.scrollIntoView({block: "nearest"});
  return {article, body};
}

async function runQuery(withAnswer) {
  if (queryBusy) return;
  const input = document.querySelector("#retrieval-query");
  const query = input.value.trim();
  if (!query) { input.setCustomValidity("Enter a question."); input.reportValidity(); input.setCustomValidity(""); return; }
  activeQuestion = query;
  const message = document.querySelector("#retrieval-message");
  message.classList.remove("error", "complete");
  const timing = document.querySelector("#retrieval-timing");
  timing.hidden = true;
  timing.textContent = "";
  const startedAt = performance.now();
  appendMessage("user", query);
  const reply = appendMessage("assistant", withAnswer ? "Finding evidence and generating your answer…" : "Searching specification excerpts…");
  setQueryBusy(true);
  message.textContent = withAnswer ? "Retrieving sources and generating an answer…" : "Searching vector sources…";
  let succeeded = false;
  try {
    const response = await fetch(withAnswer ? "/api/rag/answer" : "/api/retrieval/search", {
      method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({query})
    });
    if (!response.ok) {
      let detail;
      try { detail = (await response.json()).detail; } catch { /* Non-JSON error response. */ }
      throw new Error(detail || `HTTP ${response.status}`);
    }
    const data = await response.json();
    const evidence = withAnswer ? (data.evidence || []) : (data.results || []).map((candidate, index) => ({sourceNumber: index + 1, candidate}));
    reply.body.replaceChildren();
    if (withAnswer) renderMarkdown(reply.body, data.answer, evidence);
    else reply.body.textContent = evidence.length ? "Vector search completed. Inspect the matching excerpts in Sources." : "No matching excerpts were returned. Try a more specific model or specification.";
    const count = new Set(evidence.map(item => item.candidate.documentId)).size;
    const actions = document.createElement("div"); actions.className = "answer-actions";
    const button = document.createElement("button"); button.type = "button"; button.className = "secondary"; button.textContent = `View sources · ${count} PDF${count === 1 ? "" : "s"}`;
    button.addEventListener("click", () => openSources(evidence, null, query));
    actions.append(button); reply.article.append(actions);
    // Citation buttons retain the question associated with this exchange.
    reply.body.querySelectorAll(".citation").forEach(button => {
      button.addEventListener("click", () => { document.querySelector("#sources-question").textContent = query; });
    });
    message.textContent = `${withAnswer ? "Answer complete" : "Search complete"}. ${evidence.length} excerpts from ${count} PDFs.`;
    input.value = ""; succeeded = true;
    message.classList.add("complete");
    const elapsedSeconds = (performance.now() - startedAt) / 1000;
    timing.textContent = `Completed in ${elapsedSeconds.toFixed(1)} seconds`;
    timing.hidden = false;
  } catch (error) {
    reply.body.textContent = `Could not ${withAnswer ? "generate an answer" : "search sources"}: ${error.message}. Your question is still in the composer so you can retry.`;
    message.classList.add("error"); message.textContent = `Request failed: ${error.message}`;
  } finally {
    setQueryBusy(false);
    if (!succeeded) input.focus();
  }
}

export function initializeAssistant() {
  setQueryBusy(false);
  document.querySelector("#retrieval-form").addEventListener("submit", event => {
    event.preventDefault();
    runQuery(true);
  });
  document.querySelector("#retrieval-submit").addEventListener("click", () => runQuery(false));
}

