const savedJobKey = "document-assistant-indexing-job";
let indexingEvents = null;
let queryBusy = false;

async function getJson(url) {
  const response = await fetch(url);

  if (!response.ok) {
    const error = new Error(`HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }

  return response.json();
}

let catalog = new Map();

async function loadDocuments() {
  const message = document.querySelector("#documents-message");
  const list = document.querySelector("#document-list");

  try {
    const documents = await getJson("/api/demo/documents");
    catalog = new Map(
      documents.map(item => [item.documentId, item])
    );

    list.replaceChildren();

    for (const item of documents) {
      const row = document.createElement("li");

      const select = document.createElement("button");
      select.type = "button";
      select.className = "document-select";
      select.textContent = item.sourceFile;
      select.dataset.documentId = item.documentId;
      select.addEventListener(
        "click",
        () => selectDocument(item)
      );

      row.append(select);

      const details = document.createElement("p");
      details.textContent =
        `${item.pageCount} pages · ` +
        `Revision ${item.revision?.revision || "unspecified"} · ` +
        `Models: ${item.modelNumbers.join(", ") || "none listed"} · ` +
        `ID: ${item.documentId}`;

      row.append(details);

      const actions = document.createElement("p");

      if (item.pdfAvailable) {
        const open = pdfLink(
          item.documentId,
          "Open PDF ↗"
        );

        const download = pdfLink(
          item.documentId,
          "Download PDF"
        );

        download.removeAttribute("target");
        download.download = item.sourceFile;

        actions.append(
          open,
          document.createTextNode(" · "),
          download
        );
      } else {
        actions.textContent = "PDF unavailable";
      }

      row.append(actions);
      list.append(row);
    }

    message.textContent = documents.length
      ? `${documents.length} documents available.`
      : "No processed documents were found.";

    if (documents.length) {
      selectDocument(
        documents.find(item => item.pdfAvailable)
          || documents[0]
      );
    }
  } catch (error) {
    message.classList.add("error");
    message.textContent =
      `Could not load documents: ${error.message}`;
  }
}

function pdfUrl(documentId) {
  return `/api/demo/documents/${encodeURIComponent(documentId)}/pdf`;
}

function pdfLink(documentId, text) {
  const link = document.createElement("a");
  link.href = pdfUrl(documentId);
  link.target = "_blank";
  link.rel = "noopener";
  link.textContent = text;
  return link;
}

function selectDocument(item) {
  document.querySelectorAll(".document-select")
    .forEach(button => {
      button.setAttribute(
        "aria-current",
        String(
          button.dataset.documentId
            === item.documentId
        )
      );
    });

  document.querySelector("#pdf-title").textContent =
    item.sourceFile;

  const viewer =
    document.querySelector("#pdf-viewer");

  viewer.hidden = !item.pdfAvailable;

  document.querySelector("#pdf-actions").hidden =
    !item.pdfAvailable;

  if (!item.pdfAvailable) {
    viewer.removeAttribute("src");

    document.querySelector("#pdf-message").textContent =
      "The PDF for this document is unavailable.";

    return;
  }

  viewer.title =
    `Specification PDF: ${item.sourceFile}`;

  viewer.src = pdfUrl(item.documentId);

  const open =
    document.querySelector("#pdf-open");

  open.href = viewer.src;

  const download =
    document.querySelector("#pdf-download");

  download.href = viewer.src;
  download.download = item.sourceFile;

  document.querySelector("#pdf-message").textContent =
    "PDF selected. Use Open PDF if the embedded preview is unavailable.";
}

async function loadCorpusStatus() {
  const message =
    document.querySelector("#corpus-message");

  const differences =
    document.querySelector("#corpus-differences");

  try {
    const status =
      await getJson("/api/demo/corpus/status");

    document.querySelector(
      "#corpus-indexed"
    ).textContent = status.indexed ? "Yes" : "No";

    document.querySelector(
      "#corpus-in-sync"
    ).textContent = status.inSync ? "Yes" : "No";

    document.querySelector(
      "#corpus-catalog-count"
    ).textContent = status.catalogDocumentCount;

    document.querySelector(
      "#corpus-indexed-count"
    ).textContent = status.indexedDocumentCount;

    document.querySelector(
      "#corpus-chunk-count"
    ).textContent = status.storedChunkCount;

    const differencesText = [];

    if (status.missingDocumentIds.length > 0) {
      differencesText.push(
        `Missing from index: ${
          status.missingDocumentIds.join(", ")
        }`
      );
    }

    if (status.unexpectedDocumentIds.length > 0) {
      differencesText.push(
        `Unexpected in index: ${
          status.unexpectedDocumentIds.join(", ")
        }`
      );
    }

    differences.textContent =
      differencesText.join(" · ");

    message.textContent = status.inSync
      ? "The index matches the available corpus."
      : "The index needs attention.";
  } catch (error) {
    message.textContent =
      `Could not load corpus status: ${error.message}`;
  }
}

function displayStage(stage) {
  return stage
    .replaceAll("_", " ")
    .toLowerCase();
}

function renderIndexingStatus(status) {
  document.querySelector(
    "#indexing-stage"
  ).textContent = displayStage(status.stage);

  document.querySelector(
    "#indexing-document"
  ).textContent = status.currentDocument || "—";

  document.querySelector(
    "#indexing-discovered"
  ).textContent = status.documentsDiscovered;

  document.querySelector(
    "#indexing-chunked"
  ).textContent = status.documentsChunked;

  document.querySelector(
    "#indexing-generated"
  ).textContent = status.totalChunksGenerated;

  document.querySelector(
    "#indexing-indexed"
  ).textContent = status.documentsIndexed;

  document.querySelector(
    "#indexing-stored"
  ).textContent = status.chunksStored;

  const message =
    document.querySelector("#indexing-message");

  if (status.stage === "COMPLETED") {
    message.textContent = "Indexing completed.";
  } else if (status.stage === "FAILED") {
    message.textContent =
      `Indexing failed: ${
        status.errorMessage || "Unknown error"
      }`;
  } else {
    message.textContent =
      `Indexing: ${displayStage(status.stage)}.` +
      (
        status.currentDocument
          ? ` Current document: ${status.currentDocument}.`
          : ""
      );
  }
}

function finishIndexing() {
  indexingEvents?.close();
  indexingEvents = null;

  localStorage.removeItem(savedJobKey);

  document.querySelector(
    "#begin-indexing"
  ).disabled = false;

  loadCorpusStatus();
}

function watchIndexingJob(initialStatus) {
  indexingEvents?.close();
  renderIndexingStatus(initialStatus);

  if (initialStatus.terminal) {
    finishIndexing();
    return;
  }

  const jobId = initialStatus.jobId;
  const jobUrl =
    `/api/demo/indexing-jobs/${encodeURIComponent(jobId)}`;

  localStorage.setItem(savedJobKey, jobId);

  document.querySelector(
    "#begin-indexing"
  ).disabled = true;

  let lastSequence = initialStatus.sequence;
  let checkedAfterError = false;

  const events =
    new EventSource(`${jobUrl}/events`);

  indexingEvents = events;

  function handleEvent(event) {
    let status;

    try {
      status = JSON.parse(event.data);
    } catch {
      document.querySelector(
        "#indexing-message"
      ).textContent =
        "Received an unreadable indexing progress event.";

      return;
    }

    if (status.sequence <= lastSequence) {
      return;
    }

    lastSequence = status.sequence;
    renderIndexingStatus(status);

    if (status.terminal) {
      finishIndexing();
    }
  }

  events.addEventListener(
    "progress",
    handleEvent
  );

  events.addEventListener(
    "completed",
    handleEvent
  );

  events.addEventListener(
    "failed",
    handleEvent
  );

  events.onopen = () => {
    checkedAfterError = false;
  };

  events.onerror = async () => {
    if (indexingEvents !== events) {
      return;
    }

    document.querySelector(
      "#indexing-message"
    ).textContent =
      "Progress connection interrupted; reconnecting…";

    if (checkedAfterError) {
      return;
    }

    checkedAfterError = true;

    try {
      const status = await getJson(jobUrl);

      if (indexingEvents !== events) {
        return;
      }

      if (status.sequence > lastSequence) {
        lastSequence = status.sequence;
        renderIndexingStatus(status);
      }

      if (status.terminal) {
        finishIndexing();
      }
    } catch (error) {
      if (
        error.status === 404
        && indexingEvents === events
      ) {
        events.close();
        indexingEvents = null;

        localStorage.removeItem(savedJobKey);

        document.querySelector(
          "#begin-indexing"
        ).disabled = false;

        document.querySelector(
          "#indexing-message"
        ).textContent =
          "The previous job is no longer available. You can begin a new one.";
      }
    }
  };
}

async function beginIndexing() {
  const button =
    document.querySelector("#begin-indexing");

  const message =
    document.querySelector("#indexing-message");

  button.disabled = true;
  message.textContent =
    "Starting indexing job…";

  try {
    const response = await fetch(
      "/api/demo/indexing-jobs",
      {
        method: "POST"
      }
    );

    if (response.status === 409) {
      const problem = await response.json();

      if (!problem.activeJobId) {
        throw new Error(
          problem.detail
            || "An indexing job is already running."
        );
      }

      message.textContent =
        "Connecting to the indexing job already running…";

      const activeStatus = await getJson(
        `/api/demo/indexing-jobs/${
          encodeURIComponent(problem.activeJobId)
        }`
      );

      watchIndexingJob(activeStatus);
      return;
    }

    if (!response.ok) {
      throw new Error(
        `HTTP ${response.status}`
      );
    }

    const status = await response.json();
    watchIndexingJob(status);
  } catch (error) {
    button.disabled = false;

    message.textContent =
      `Could not start indexing: ${error.message}`;
  }
}

async function resumeSavedJob() {
  const jobId =
    localStorage.getItem(savedJobKey);

  if (!jobId) {
    return;
  }

  const button =
    document.querySelector("#begin-indexing");

  const message =
    document.querySelector("#indexing-message");

  button.disabled = true;

  message.textContent =
    "Checking the previous indexing job…";

  try {
    const status = await getJson(
      `/api/demo/indexing-jobs/${
        encodeURIComponent(jobId)
      }`
    );

    watchIndexingJob(status);
  } catch (error) {
    localStorage.removeItem(savedJobKey);
    button.disabled = false;

    message.textContent =
      error.status === 404
        ? "The previous job is no longer available. You can begin a new one."
        : `Could not reconnect to the previous job: ${error.message}`;
  }
}

// Deliberately render a limited Markdown subset
// with DOM nodes, never model HTML.
// Supports paragraphs, headings, lists, quotes,
// fenced code, tables, and inline formatting.
function inlineMarkdown(
    parent,
    text,
    evidence = []) {

  const validSources = new Set(
    evidence.map(item => item.sourceNumber)
  );

  const pattern =
    /(\[[0-9]+\]|`[^`\n]+`|\*\*[^*\n]+\*\*|\*[^*\n]+\*|\[[^\]\n]+\]\([^\s)]+\))/g;

  let start = 0;

  for (const match of text.matchAll(pattern)) {
    parent.append(
      document.createTextNode(
        text.slice(start, match.index)
      )
    );

    const token = match[0];

    if (
      /^\[\d+\]$/.test(token)
      && validSources.has(
        Number(token.slice(1, -1))
      )
    ) {
      const button =
        document.createElement("button");

      button.type = "button";
      button.className = "citation";
      button.textContent = token;

      button.setAttribute(
        "aria-label",
        `Inspect source ${token}`
      );

      button.addEventListener(
        "click",
        () => openSources(
          evidence,
          Number(token.slice(1, -1))
        )
      );

      parent.append(button);
    } else if (token.startsWith("`")) {
      const code =
        document.createElement("code");

      code.textContent =
        token.slice(1, -1);

      parent.append(code);
    } else if (
      token.startsWith("**")
      || token.startsWith("*")
    ) {
      const strong =
        token.startsWith("**");

      const node =
        document.createElement(
          strong ? "strong" : "em"
        );

      inlineMarkdown(
        node,
        token.slice(
          strong ? 2 : 1,
          strong ? -2 : -1
        ),
        evidence
      );

      parent.append(node);
    } else if (token.includes("](")) {
      const linkMatch = token.match(
        /^\[([^\]]+)\]\(([^)]+)\)$/
      );

      let url;

      try {
        url = new URL(linkMatch[2]);
      } catch {
        url = null;
      }

      if (
        url
        && ["https:", "http:"]
          .includes(url.protocol)
      ) {
        const link =
          document.createElement("a");

        link.href = url.href;
        link.target = "_blank";
        link.rel = "noopener noreferrer";
        link.textContent = linkMatch[1];

        parent.append(link);
      } else {
        parent.append(
          document.createTextNode(token)
        );
      }
    } else {
      parent.append(
        document.createTextNode(token)
      );
    }

    start = match.index + token.length;
  }

  parent.append(
    document.createTextNode(
      text.slice(start)
    )
  );
}

function renderMarkdown(
    container,
    text,
    evidence) {

  const lines = String(text ?? "")
    .replace(/\r\n?/g, "\n")
    .split("\n");

  const isTableRule = line =>
    /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$/
      .test(line);

  const cells = line =>
    line.trim()
      .replace(/^\|/, "")
      .replace(/\|$/, "")
      .split("|")
      .map(cell => cell.trim());

  for (let i = 0; i < lines.length;) {
    const line = lines[i];

    if (!line.trim()) {
      i++;
      continue;
    }

    if (/^\s*```/.test(line)) {
      const pre =
        document.createElement("pre");

      const code =
        document.createElement("code");

      const block = [];
      i++;

      while (
        i < lines.length
        && !/^\s*```/.test(lines[i])
      ) {
        block.push(lines[i++]);
      }

      if (i < lines.length) {
        i++;
      }

      code.textContent = block.join("\n");
      pre.append(code);
      container.append(pre);
      continue;
    }

    if (
      i + 1 < lines.length
      && line.includes("|")
      && isTableRule(lines[i + 1])
    ) {
      const wrap =
        document.createElement("div");

      wrap.className = "table-wrap";

      const table =
        document.createElement("table");

      const head =
        document.createElement("thead");

      const row =
        document.createElement("tr");

      for (const value of cells(line)) {
        const th =
          document.createElement("th");

        th.scope = "col";
        inlineMarkdown(th, value, evidence);
        row.append(th);
      }

      head.append(row);
      table.append(head);

      const body =
        document.createElement("tbody");

      i += 2;

      while (
        i < lines.length
        && lines[i].trim()
        && lines[i].includes("|")
      ) {
        const tr =
          document.createElement("tr");

        for (const value of cells(lines[i++])) {
          const td =
            document.createElement("td");

          inlineMarkdown(
            td,
            value,
            evidence
          );

          tr.append(td);
        }

        body.append(tr);
      }

      table.append(body);
      wrap.append(table);
      container.append(wrap);
      continue;
    }

    const heading =
      line.match(/^(#{1,6})\s+(.+)$/);

    if (heading) {
      const node = document.createElement(
        `h${Math.min(
          heading[1].length + 2,
          6
        )}`
      );

      inlineMarkdown(
        node,
        heading[2],
        evidence
      );

      container.append(node);
      i++;
      continue;
    }

    const listMatch = line.match(
      /^\s*(?:([-*+])|\d+[.)])\s+(.+)$/
    );

    if (listMatch) {
      const ordered = !listMatch[1];

      const list =
        document.createElement(
          ordered ? "ol" : "ul"
        );

      if (ordered) {
        list.start = Number(
          line.trim().match(/^\d+/)[0]
        );
      }

      while (i < lines.length) {
        const item = lines[i].match(
          /^\s*(?:([-*+])|\d+[.)])\s+(.+)$/
        );

        if (
          !item
          || Boolean(!item[1]) !== ordered
        ) {
          break;
        }

        const li =
          document.createElement("li");

        inlineMarkdown(
          li,
          item[2],
          evidence
        );

        list.append(li);
        i++;
      }

      container.append(list);
      continue;
    }

    if (/^>\s?/.test(line)) {
      const quote =
        document.createElement("blockquote");

      const block = [];

      while (
        i < lines.length
        && /^>\s?/.test(lines[i])
      ) {
        block.push(
          lines[i++].replace(/^>\s?/, "")
        );
      }

      inlineMarkdown(
        quote,
        block.join(" "),
        evidence
      );

      container.append(quote);
      continue;
    }

    const paragraph =
      document.createElement("p");

    inlineMarkdown(
      paragraph,
      line,
      evidence
    );

    i++;

    while (
      i < lines.length
      && lines[i].trim()
      && !/^(\s*```|#{1,6}\s|\s*[-*+]\s|\s*\d+[.)]\s|>)/
        .test(lines[i])
      && !(
        i + 1 < lines.length
        && isTableRule(lines[i + 1])
      )
    ) {
      paragraph.append(
        document.createElement("br")
      );

      inlineMarkdown(
        paragraph,
        lines[i++],
        evidence
      );
    }

    container.append(paragraph);
  }
}

const sourcesDialog =
  document.querySelector("#sources-dialog");

let activeQuestion = "";

function openSources(
    evidence,
    sourceNumber = null,
    question = activeQuestion) {

  const container =
    document.querySelector("#retrieval-results");

  container.replaceChildren();

  const groups = new Map();

  for (const item of evidence) {
    const id = item.candidate.documentId;

    if (!groups.has(id)) {
      groups.set(id, []);
    }

    groups.get(id).push(item);
  }

  document.querySelector(
    "#sources-title"
  ).textContent =
    `Sources · ${groups.size} PDF${
      groups.size === 1 ? "" : "s"
    }`;

  document.querySelector(
    "#sources-question"
  ).textContent = question;

  if (!evidence.length) {
    const empty =
      document.createElement("p");

    empty.textContent =
      "No supporting excerpts were returned.";

    container.append(empty);
  }

  for (const [id, items] of groups) {
    const card =
      document.createElement("article");

    card.className = "source-document";

    const title =
      document.createElement("h3");

    title.textContent =
      items[0].candidate.sourceFile || id;

    card.append(title);

    const catalogDocument =
      catalog.get(id);

    if (catalogDocument?.pdfAvailable) {
      card.append(
        pdfLink(id, "Open PDF ↗")
      );

      const inspect =
        window.document.createElement("button");

      inspect.type = "button";
      inspect.className = "secondary";
      inspect.textContent =
        "Inspect in Corpus";

      inspect.addEventListener(
        "click",
        () => {
          sourcesDialog.close();
          activateTab("tab-corpus", true);
          selectDocument(catalogDocument);
        }
      );

      const actions =
        window.document.createElement("div");

      actions.className = "button-group";
      actions.append(inspect);
      card.append(actions);
    } else {
      const unavailable =
        window.document.createElement("p");

      unavailable.className = "subtle";

      unavailable.textContent =
        catalogDocument
          ? "PDF unavailable in the catalog."
          : "PDF availability could not be verified in the catalog.";

      card.append(unavailable);
    }

    for (const item of items) {
      const details =
        window.document.createElement("details");

      details.className = "source-excerpt";
      details.id =
        `source-${item.sourceNumber}`;

      details.tabIndex = -1;
      details.open =
        item.sourceNumber === sourceNumber;

      const summary =
        window.document.createElement("summary");

      summary.textContent =
        `[${item.sourceNumber}] ${
          item.candidate.section
            || "Document excerpt"
        }`;

      if (
        typeof item.candidate.rrfScore
          === "number"
        && Number.isFinite(
          item.candidate.rrfScore
        )
      ) {
        const score =
          window.document.createElement("span");

        score.className = "rrf";

        score.textContent =
          ` · RRF ${
            item.candidate.rrfScore.toFixed(5)
          }`;

        summary.append(score);
      }

      const text =
        window.document.createElement("pre");

      text.className = "source-text";
      text.textContent = item.candidate.text;

      details.append(summary, text);
      card.append(details);
    }

    container.append(card);
  }

  if (!sourcesDialog.open) {
    sourcesDialog.showModal();
  }

  if (sourceNumber !== null) {
    requestAnimationFrame(() => {
      const target =
        window.document.getElementById(
          `source-${sourceNumber}`
        );

      target?.focus();

      target?.scrollIntoView({
        block: "nearest"
      });
    });
  }
}

document.querySelector(
  "#sources-close"
).addEventListener(
  "click",
  () => sourcesDialog.close()
);

sourcesDialog.addEventListener(
  "click",
  event => {
    if (event.target !== sourcesDialog) {
      return;
    }

    const rect =
      sourcesDialog.getBoundingClientRect();

    if (
      event.clientX < rect.left
      || event.clientX > rect.right
      || event.clientY < rect.top
      || event.clientY > rect.bottom
    ) {
      sourcesDialog.close();
    }
  }
);

const tabs = [
  ...document.querySelectorAll(
    '[role="tab"]'
  )
];

function activateTab(id, focus = false) {
  for (const tab of tabs) {
    const selected = tab.id === id;

    tab.setAttribute(
      "aria-selected",
      String(selected)
    );

    tab.tabIndex = selected ? 0 : -1;

    document.getElementById(
      tab.getAttribute("aria-controls")
    ).hidden = !selected;

    if (selected && focus) {
      tab.focus();
    }
  }
}

tabs.forEach((tab, index) => {
  tab.addEventListener(
    "click",
    () => activateTab(tab.id)
  );

  tab.addEventListener(
    "keydown",
    event => {
      let next;

      if (event.key === "ArrowRight") {
        next = (index + 1) % tabs.length;
      }

      if (event.key === "ArrowLeft") {
        next =
          (index + tabs.length - 1)
          % tabs.length;
      }

      if (event.key === "Home") {
        next = 0;
      }

      if (event.key === "End") {
        next = tabs.length - 1;
      }

      if (next !== undefined) {
        event.preventDefault();
        activateTab(tabs[next].id, true);
      }
    }
  );
});

function setQueryBusy(busy) {
  queryBusy = busy;

  for (const id of [
    "answer-submit",
    "retrieval-submit",
    "retrieval-query"
  ]) {
    document.getElementById(id).disabled =
      busy;
  }

  document.querySelector(
    "#conversation"
  ).setAttribute(
    "aria-busy",
    String(busy)
  );

  document.querySelector(
    "#retrieval-message"
  ).classList.toggle("busy", busy);
}

function appendMessage(kind, text) {
  document.querySelector(
    "#chat-empty"
  ).hidden = true;

  const article =
    document.createElement("article");

  article.className =
    `message ${kind}-message`;

  const label =
    document.createElement("div");

  label.className = "message-label";

  label.textContent =
    kind === "user"
      ? "You"
      : "Document Assistant";

  const body =
    document.createElement("div");

  body.className =
    kind === "assistant"
      ? "markdown"
      : "";

  body.textContent = text;

  article.append(label, body);

  document.querySelector(
    "#conversation"
  ).append(article);

  article.scrollIntoView({
    block: "nearest"
  });

  return {article, body};
}

async function runQuery(withAnswer) {
  if (queryBusy) {
    return;
  }

  const input =
    document.querySelector("#retrieval-query");

  const query = input.value.trim();

  if (!query) {
    input.setCustomValidity(
      "Enter a question."
    );

    input.reportValidity();
    input.setCustomValidity("");
    return;
  }

  activeQuestion = query;

  const message =
    document.querySelector("#retrieval-message");

  message.classList.remove(
    "error",
    "complete"
  );

  const timing =
    document.querySelector("#retrieval-timing");

  timing.hidden = true;
  timing.textContent = "";

  const startedAt = performance.now();

  appendMessage("user", query);

  const reply = appendMessage(
    "assistant",
    withAnswer
      ? "Finding evidence and generating your answer…"
      : "Searching specification excerpts…"
  );

  setQueryBusy(true);

  message.textContent = withAnswer
    ? "Retrieving sources and generating an answer…"
    : "Searching vector sources…";

  let succeeded = false;

  try {
    const response = await fetch(
      withAnswer
        ? "/api/rag/answer"
        : "/api/retrieval/search",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({query})
      }
    );

    if (!response.ok) {
      let detail;

      try {
        detail =
          (await response.json()).detail;
      } catch {
        // Non-JSON error response.
      }

      throw new Error(
        detail || `HTTP ${response.status}`
      );
    }

    const data = await response.json();

    const evidence = withAnswer
      ? (data.evidence || [])
      : (data.results || []).map(
          (candidate, index) => ({
            sourceNumber: index + 1,
            candidate
          })
        );

    reply.body.replaceChildren();

    if (withAnswer) {
      renderMarkdown(
        reply.body,
        data.answer,
        evidence
      );
    } else {
      reply.body.textContent =
        evidence.length
          ? "Vector search completed. Inspect the matching excerpts in Sources."
          : "No matching excerpts were returned. Try a more specific model or specification.";
    }

    const count = new Set(
      evidence.map(
        item => item.candidate.documentId
      )
    ).size;

    const actions =
      document.createElement("div");

    actions.className = "answer-actions";

    const button =
      document.createElement("button");

    button.type = "button";
    button.className = "secondary";

    button.textContent =
      `View sources · ${count} PDF${
        count === 1 ? "" : "s"
      }`;

    button.addEventListener(
      "click",
      () => openSources(
        evidence,
        null,
        query
      )
    );

    actions.append(button);
    reply.article.append(actions);

    reply.body.querySelectorAll(
      ".citation"
    ).forEach(citationButton => {
      citationButton.addEventListener(
        "click",
        () => {
          document.querySelector(
            "#sources-question"
          ).textContent = query;
        }
      );
    });

    message.textContent =
      `${withAnswer
        ? "Answer complete"
        : "Search complete"
      }. ${evidence.length} excerpts ` +
      `from ${count} PDF${
        count === 1 ? "" : "s"
      }.`;

    input.value = "";
    succeeded = true;

    message.classList.add("complete");

    const elapsedSeconds =
      (performance.now() - startedAt)
      / 1000;

    timing.textContent =
      `Completed in ${
        elapsedSeconds.toFixed(1)
      } seconds`;

    timing.hidden = false;
  } catch (error) {
    reply.body.textContent =
      `Could not ${
        withAnswer
          ? "generate an answer"
          : "search sources"
      }: ${error.message}. ` +
      "Your question is still in the composer so you can retry.";

    message.classList.add("error");

    message.textContent =
      `Request failed: ${error.message}`;
  } finally {
    setQueryBusy(false);

    if (!succeeded) {
      input.focus();
    }
  }
}

document.querySelector(
  "#begin-indexing"
).disabled = false;

document.querySelector(
  "#begin-indexing"
).addEventListener(
  "click",
  beginIndexing
);

setQueryBusy(false);

document.querySelector(
  "#retrieval-form"
).addEventListener(
  "submit",
  event => {
    event.preventDefault();
    runQuery(true);
  }
);

document.querySelector(
  "#retrieval-submit"
).addEventListener(
  "click",
  () => runQuery(false)
);

for (
  const img of document.querySelectorAll(
    ".plot-card img"
  )
) {
  const update = () => {
    const failed = !img.naturalWidth;

    img.parentElement.hidden = failed;

    img.closest("figure")
      .querySelector(
        ".plot-unavailable"
      ).hidden = !failed;
  };

  img.addEventListener("load", update);
  img.addEventListener("error", update);

  if (img.complete) {
    update();
  }
}

loadDocuments();
loadCorpusStatus();
resumeSavedJob();

// Evaluation k values are validated in the browser
// and again by the server.
const evaluationButton =
  document.querySelector("#run-evaluation");

const evaluationMessage =
  document.querySelector("#evaluation-message");

const evaluationKInput =
  document.querySelector("#evaluation-k-values");

const evaluationKError =
  document.querySelector("#evaluation-k-error");

let evaluationTimer = null;
let evaluationStarting = false;
let evaluationBusy = false;
let evaluationStatusKnown = false;
let evaluationRunKValues = null;

// Empirical estimates calibrated from the completed
// 50-question run at k=5,10,20,30,40.
// The full run took about three hours and cost
// approximately one dollar.
const evaluationSettings = Object.freeze({
  defaultKValues: [5, 10, 20, 30, 40],
  maxK: 40,
  questionCount: 50,

  // Runtime includes fixed overhead for each full
  // question-suite pass plus work proportional to k.
  baseMinutesPerK: 18,
  minutesPerEvidenceChunk: 18 / 21,

  // Each question/k pair makes an answer call and
  // a judge call. Tiny embedding calls are excluded.
  modelCallsPerCase: 2,

  // Average significant-call cost at the reference
  // evidence size observed in the completed run.
  usdPerModelCall: 0.002,
  costBaseK: 5,
  costReferenceK: 21
});

function parseEvaluationKValues(text, maxK) {
  const parts = text.trim()
    .split(/[,\s]+/)
    .filter(Boolean);

  if (!parts.length) {
    throw new Error(
      "Enter at least one evidence limit."
    );
  }

  if (parts.length > 100) {
    throw new Error(
      "Enter no more than 100 values."
    );
  }

  if (
    parts.some(
      part =>
        !/^[1-9]\d*$/.test(part)
        || !Number.isSafeInteger(Number(part))
        || Number(part) > maxK
    )
  ) {
    throw new Error(
      `Each k must be a whole number from 1 to ${maxK}.`
    );
  }

  return [
    ...new Set(parts.map(Number))
  ].sort((a, b) => a - b);
}

function calculateEvaluationEstimate(
    settings,
    kValues) {

  const kSum = kValues.reduce(
    (sum, k) => sum + k,
    0
  );

  const minutes =
    settings.baseMinutesPerK
      * kValues.length
    + settings.minutesPerEvidenceChunk
      * kSum;

  const seconds = minutes * 60;

  const cases =
    settings.questionCount
    * kValues.length;

  const modelCalls =
    cases
    * settings.modelCallsPerCase;

  const costWeight =
    (
      kSum
      + settings.costBaseK
        * kValues.length
    )
    / (
      settings.costReferenceK
      + settings.costBaseK
    );

  const usd =
    settings.questionCount
    * settings.modelCallsPerCase
    * settings.usdPerModelCall
    * costWeight;

  return {
    seconds,
    usd,
    cases,
    modelCalls,
    kSum,
    costWeight
  };
}

function formatEvaluationDuration(seconds) {
  if (seconds < 60) {
    return `${Math.ceil(seconds)} sec`;
  }

  if (seconds < 3600) {
    return `${(seconds / 60).toFixed(1)} min`;
  }

  return `${(seconds / 3600).toFixed(1)} hr`;
}

function formatEvaluationCost(usd) {
  return new Intl.NumberFormat(
    "en-US",
    {
      style: "currency",
      currency: "USD",
      minimumFractionDigits:
        usd < 1 ? 4 : 2,
      maximumFractionDigits:
        usd < 1 ? 4 : 2
    }
  ).format(usd);
}

function updateEvaluationPlan() {
  let valid = false;

  try {
    const ks = parseEvaluationKValues(
      evaluationKInput.value,
      evaluationSettings.maxK
    );

    const estimate =
      calculateEvaluationEstimate(
        evaluationSettings,
        ks
      );

    evaluationKError.hidden = true;

    evaluationKInput.removeAttribute(
      "aria-invalid"
    );

    document.querySelector(
      "#evaluation-estimate"
    ).textContent =
      `Estimated runtime: ~${
        formatEvaluationDuration(
          estimate.seconds
        )
      } · Estimated model cost: ~${
        formatEvaluationCost(
          estimate.usd
        )
      }`;

    document.querySelector(
      "#evaluation-estimate-basis"
    ).textContent =
      `Time: (${ks.length} k value${
        ks.length === 1 ? "" : "s"
      } × ~${
        evaluationSettings.baseMinutesPerK
      } fixed min) + (${
        estimate.kSum
      } total evidence slots × ~${
        evaluationSettings
          .minutesPerEvidenceChunk
          .toFixed(2)
      } min) = ~${
        formatEvaluationDuration(
          estimate.seconds
        )
      }. Cost: ${
        evaluationSettings.questionCount
      } questions × ${
        evaluationSettings.modelCallsPerCase
      } calls/question-k × ~${
        formatEvaluationCost(
          evaluationSettings.usdPerModelCall
        )
      }/call at k≈${
        evaluationSettings.costReferenceK
      }, weighted by the selected k values = ~${
        formatEvaluationCost(
          estimate.usd
        )
      }. Calibrated from the last full ` +
      "GPT-oss-120b run; actual time and cost " +
      "vary with context length, retries, and " +
      "provider load. Relevance labeling is excluded.";

    valid = true;
  } catch (error) {
    evaluationKError.hidden = false;
    evaluationKError.textContent =
      error.message;

    evaluationKInput.setAttribute(
      "aria-invalid",
      "true"
    );

    document.querySelector(
      "#evaluation-estimate"
    ).textContent =
      "Enter valid k values to see the estimate.";

    document.querySelector(
      "#evaluation-estimate-basis"
    ).textContent = "";
  }

  evaluationKInput.disabled =
    evaluationBusy
    || evaluationStarting;

  evaluationButton.disabled =
    evaluationBusy
    || evaluationStarting
    || !evaluationStatusKnown
    || !valid;
}

function initializeEvaluationSettings() {
  evaluationKInput.value =
    evaluationSettings.defaultKValues.join(", ");

  document.querySelector(
    "#evaluation-k-help"
  ).textContent =
    "Enter comma-separated whole numbers " +
    `from 1 to ${evaluationSettings.maxK}. ` +
    "Each distinct value runs the entire " +
    "question suite.";

  updateEvaluationPlan();
}

evaluationKInput.addEventListener(
  "input",
  updateEvaluationPlan
);

evaluationKInput.addEventListener(
  "blur",
  () => {
    try {
      evaluationKInput.value =
        parseEvaluationKValues(
          evaluationKInput.value,
          evaluationSettings.maxK
        ).join(", ");
    } catch {
      // Preserve invalid input for correction.
    }

    updateEvaluationPlan();
  }
);

function refreshEvaluationPlots(version) {
  for (
    const img of document.querySelectorAll(
      ".plot-card img"
    )
  ) {
    const url = new URL(img.src);

    url.searchParams.set("v", version);

    img.closest("figure")
      .querySelector("a").href = url.href;

    img.src = url.href;
  }
}

function showEvaluationStatus(status) {
  evaluationStatusKnown = true;
  evaluationBusy = !status.terminal;

  evaluationRunKValues =
    status.kValues?.length
      ? status.kValues
      : null;

  if (
    evaluationBusy
    && evaluationRunKValues
  ) {
    evaluationKInput.value =
      evaluationRunKValues.join(", ");
  }

  updateEvaluationPlan();

  evaluationMessage.classList.toggle(
    "busy",
    evaluationBusy
  );

  evaluationMessage.classList.toggle(
    "error",
    status.stage === "FAILED"
  );

  evaluationMessage.classList.toggle(
    "complete",
    status.stage === "COMPLETED"
  );

  evaluationMessage.textContent =
    status.message;

  if (status.kValues?.length) {
    evaluationMessage.textContent +=
      ` Tested k: ${
        status.kValues.join(", ")
      }.`;
  }

  if (
    status.terminal
    && status.startedAt
    && status.finishedAt
  ) {
    const seconds =
      (
        Date.parse(status.finishedAt)
        - Date.parse(status.startedAt)
      )
      / 1000;

    if (Number.isFinite(seconds)) {
      evaluationMessage.textContent +=
        ` Elapsed: ${
          Math.round(seconds)
        } seconds.`;
    }
  }

  if (status.stage === "COMPLETED") {
    refreshEvaluationPlots(status.jobId);
  }

  if (evaluationBusy) {
    evaluationTimer = setTimeout(
      () => pollEvaluation(status.jobId),
      3000
    );
  }
}

async function pollEvaluation(jobId) {
  clearTimeout(evaluationTimer);

  try {
    const status = await getJson(
      `/api/demo/evaluation-jobs/${
        encodeURIComponent(jobId)
      }`
    );

    showEvaluationStatus(status);
  } catch (error) {
    if (error.status === 404) {
      evaluationStatusKnown = true;
      evaluationBusy = false;

      updateEvaluationPlan();

      evaluationMessage.classList.remove(
        "busy",
        "complete"
      );

      evaluationMessage.classList.add(
        "error"
      );

      evaluationMessage.textContent =
        "This evaluation job is no longer " +
        "available, possibly because the " +
        "application restarted. Check its " +
        "report before starting another run.";
    } else {
      evaluationMessage.textContent =
        "Could not read evaluation progress. " +
        "Retrying…";

      evaluationTimer = setTimeout(
        () => pollEvaluation(jobId),
        3000
      );
    }
  }
}

async function resumeEvaluation() {
  clearTimeout(evaluationTimer);

  try {
    const response = await fetch(
      "/api/demo/evaluation-jobs/current",
      {
        cache: "no-store"
      }
    );

    if (!response.ok) {
      throw new Error(
        `HTTP ${response.status}`
      );
    }

    if (response.status === 204) {
      evaluationStatusKnown = true;
      evaluationBusy = false;

      updateEvaluationPlan();

      evaluationMessage.classList.remove(
        "busy",
        "error",
        "complete"
      );

      evaluationMessage.textContent =
        "Ready to run the evaluation.";
    } else {
      showEvaluationStatus(
        await response.json()
      );
    }
  } catch (error) {
    evaluationStatusKnown = false;

    updateEvaluationPlan();

    evaluationMessage.classList.remove(
      "busy",
      "complete"
    );

    evaluationMessage.classList.add(
      "error"
    );

    evaluationMessage.textContent =
      "Could not check evaluation status: " +
      `${error.message}. Reload to retry.`;
  }
}

evaluationButton.addEventListener(
  "click",
  async () => {
    if (
      evaluationStarting
      || evaluationButton.disabled
    ) {
      return;
    }

    const kValues =
      parseEvaluationKValues(
        evaluationKInput.value,
        evaluationSettings.maxK
      );

    evaluationStarting = true;
    updateEvaluationPlan();

    evaluationMessage.classList.remove(
      "error",
      "complete"
    );

    evaluationMessage.classList.add(
      "busy"
    );

    evaluationMessage.textContent =
      "Starting evaluation…";

    try {
      const response = await fetch(
        "/api/demo/evaluation-jobs",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json"
          },
          body: JSON.stringify({kValues})
        }
      );

      if (response.status === 409) {
        await resumeEvaluation();
        return;
      }

      if (!response.ok) {
        let detail;

        try {
          detail =
            (await response.json()).detail;
        } catch {
          // Empty or non-JSON error.
        }

        throw new Error(
          detail || `HTTP ${response.status}`
        );
      }

      showEvaluationStatus(
        await response.json()
      );
    } catch (error) {
      // A lost POST response may still have
      // started a job. Check before allowing retry.
      await resumeEvaluation();

      if (
        !evaluationBusy
        && evaluationStatusKnown
      ) {
        evaluationMessage.classList.remove(
          "busy",
          "complete"
        );

        evaluationMessage.classList.add(
          "error"
        );

        evaluationMessage.textContent =
          `Could not start evaluation: ${
            error.message
          }.`;
      }
    } finally {
      evaluationStarting = false;
      updateEvaluationPlan();
    }
  }
);

initializeEvaluationSettings();
resumeEvaluation();
