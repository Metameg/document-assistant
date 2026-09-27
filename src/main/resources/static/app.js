const savedJobKey = "document-assistant-indexing-job";
let indexingEvents = null;

async function getJson(url) {
  const response = await fetch(url);

  if (!response.ok) {
    const error = new Error(`HTTP ${response.status}`);
    error.status = response.status;
    throw error;
  }

  return response.json();
}

async function loadDocuments() {
  const message = document.querySelector("#documents-message");
  const list = document.querySelector("#document-list");

  try {
    const documents = await getJson("/api/demo/documents");
    list.replaceChildren();

    if (documents.length === 0) {
      message.textContent = "No processed documents were found.";
      return;
    }

    for (const item of documents) {
      const row = document.createElement("li");

      const title = document.createElement("strong");
      title.textContent = item.sourceFile;
      row.append(title);

      const details = document.createElement("p");
      const revision = item.revision?.revision || "unspecified";
      const models = item.modelNumbers.join(", ") || "none listed";
      details.textContent =
        `ID: ${item.documentId} · Pages: ${item.pageCount} · ` +
        `Revision: ${revision} · Models: ${models}`;
      row.append(details);

      if (item.pdfAvailable) {
        const pdfUrl =
          `/api/demo/documents/${encodeURIComponent(item.documentId)}/pdf`;

        const openLink = document.createElement("a");
        openLink.href = pdfUrl;
        openLink.target = "_blank";
        openLink.rel = "noopener";
        openLink.textContent = "Open PDF";
        row.append(openLink);

        row.append(document.createTextNode(" · "));

        const downloadLink = document.createElement("a");
        downloadLink.href = pdfUrl;
        downloadLink.download = item.sourceFile;
        downloadLink.textContent = "Download PDF";
        row.append(downloadLink);
      } else {
        const unavailable = document.createElement("span");
        unavailable.textContent = "PDF unavailable";
        row.append(unavailable);
      }

      list.append(row);
    }

    message.textContent = `${documents.length} documents found.`;
  } catch (error) {
    message.textContent = `Could not load documents: ${error.message}`;
  }
}

async function loadCorpusStatus() {
  const message = document.querySelector("#corpus-message");
  const differences = document.querySelector("#corpus-differences");

  try {
    const status = await getJson("/api/demo/corpus/status");

    document.querySelector("#corpus-indexed").textContent =
      status.indexed ? "Yes" : "No";
    document.querySelector("#corpus-in-sync").textContent =
      status.inSync ? "Yes" : "No";
    document.querySelector("#corpus-catalog-count").textContent =
      status.catalogDocumentCount;
    document.querySelector("#corpus-indexed-count").textContent =
      status.indexedDocumentCount;
    document.querySelector("#corpus-chunk-count").textContent =
      status.storedChunkCount;

    const differencesText = [];
    if (status.missingDocumentIds.length > 0) {
      differencesText.push(
        `Missing from index: ${status.missingDocumentIds.join(", ")}`
      );
    }
    if (status.unexpectedDocumentIds.length > 0) {
      differencesText.push(
        `Unexpected in index: ${status.unexpectedDocumentIds.join(", ")}`
      );
    }

    differences.textContent = differencesText.join(" · ");
    message.textContent = status.inSync
      ? "The index matches the available corpus."
      : "The index needs attention.";
  } catch (error) {
    message.textContent = `Could not load corpus status: ${error.message}`;
  }
}

function displayStage(stage) {
  return stage.replaceAll("_", " ").toLowerCase();
}

function renderIndexingStatus(status) {
  document.querySelector("#indexing-stage").textContent =
    displayStage(status.stage);
  document.querySelector("#indexing-document").textContent =
    status.currentDocument || "—";
  document.querySelector("#indexing-discovered").textContent =
    status.documentsDiscovered;
  document.querySelector("#indexing-chunked").textContent =
    status.documentsChunked;
  document.querySelector("#indexing-generated").textContent =
    status.totalChunksGenerated;
  document.querySelector("#indexing-indexed").textContent =
    status.documentsIndexed;
  document.querySelector("#indexing-stored").textContent =
    status.chunksStored;

  const message = document.querySelector("#indexing-message");

  if (status.stage === "COMPLETED") {
    message.textContent = "Indexing completed.";
  } else if (status.stage === "FAILED") {
    message.textContent =
      `Indexing failed: ${status.errorMessage || "Unknown error"}`;
  } else {
    message.textContent =
      `Indexing: ${displayStage(status.stage)}.` +
      (status.currentDocument
        ? ` Current document: ${status.currentDocument}.`
        : "");
  }
}

function finishIndexing() {
  indexingEvents?.close();
  indexingEvents = null;
  localStorage.removeItem(savedJobKey);
  document.querySelector("#begin-indexing").disabled = false;
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
  document.querySelector("#begin-indexing").disabled = true;

  let lastSequence = initialStatus.sequence;
  let checkedAfterError = false;

  const events = new EventSource(`${jobUrl}/events`);
  indexingEvents = events;

  function handleEvent(event) {
    let status;

    try {
      status = JSON.parse(event.data);
    } catch {
      document.querySelector("#indexing-message").textContent =
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

  events.addEventListener("progress", handleEvent);
  events.addEventListener("completed", handleEvent);
  events.addEventListener("failed", handleEvent);

  events.onopen = () => {
    checkedAfterError = false;
  };

  events.onerror = async () => {
    if (indexingEvents !== events) {
      return;
    }

    document.querySelector("#indexing-message").textContent =
      "Progress connection interrupted; reconnecting…";

    // EventSource reconnects automatically. Check once after an
    // interruption in case the job finished while disconnected.
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
      if (error.status === 404 && indexingEvents === events) {
        events.close();
        indexingEvents = null;
        localStorage.removeItem(savedJobKey);
        document.querySelector("#begin-indexing").disabled = false;
        document.querySelector("#indexing-message").textContent =
          "The previous job is no longer available. You can begin a new one.";
      }
    }
  };
}

async function beginIndexing() {
  const button = document.querySelector("#begin-indexing");
  const message = document.querySelector("#indexing-message");

  button.disabled = true;
  message.textContent = "Starting indexing job…";

  try {
    const response = await fetch("/api/demo/indexing-jobs", {
      method: "POST"
    });

    if (response.status === 409) {
      const problem = await response.json();

      if (!problem.activeJobId) {
        throw new Error(problem.detail || "An indexing job is already running.");
      }

      message.textContent = "Connecting to the indexing job already running…";
      const activeStatus = await getJson(
        `/api/demo/indexing-jobs/${encodeURIComponent(problem.activeJobId)}`
      );
      watchIndexingJob(activeStatus);
      return;
    }

    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }

    const status = await response.json();
    watchIndexingJob(status);
  } catch (error) {
    button.disabled = false;
    message.textContent = `Could not start indexing: ${error.message}`;
  }
}

async function resumeSavedJob() {
  const jobId = localStorage.getItem(savedJobKey);

  if (!jobId) {
    return;
  }

  const button = document.querySelector("#begin-indexing");
  const message = document.querySelector("#indexing-message");

  button.disabled = true;
  message.textContent = "Checking the previous indexing job…";

  try {
    const status = await getJson(
      `/api/demo/indexing-jobs/${encodeURIComponent(jobId)}`
    );
    watchIndexingJob(status);
  } catch (error) {
    localStorage.removeItem(savedJobKey);
    button.disabled = false;
    message.textContent = error.status === 404
      ? "The previous job is no longer available. You can begin a new one."
      : `Could not reconnect to the previous job: ${error.message}`;
  }
}

function addDetail(list, label, value) {
  const term = document.createElement("dt");
  term.textContent = label;

  const description = document.createElement("dd");
  description.textContent =
    value === null || value === undefined || value === ""
      ? "—"
      : String(value);

  list.append(term, description);
}

function renderRetrievalResults(response) {
  const container = document.querySelector("#retrieval-results");
  container.replaceChildren();

  response.results.forEach((chunk, index) => {
    const card = document.createElement("article");

    const heading = document.createElement("h3");
    heading.textContent = `Result ${index + 1}`;
    card.append(heading);

    const metadata = document.createElement("dl");
    addDetail(
      metadata,
      "Similarity score",
      Number(chunk.similarityScore).toFixed(4)
    );
    addDetail(metadata, "Document ID", chunk.documentId);
    addDetail(metadata, "Source filename", chunk.sourceFile);
    addDetail(metadata, "Revision", chunk.revision);
    addDetail(metadata, "Revision part number", chunk.revisionPartNumber);
    addDetail(metadata, "Publication date", chunk.publicationDate);
    addDetail(metadata, "Chunk index", chunk.chunkIndex);
    addDetail(metadata, "Section", chunk.section);
    addDetail(metadata, "Model numbers", chunk.modelNumbers.join(", "));
    addDetail(metadata, "Page numbers", chunk.pageNumbers.join(", "));
    addDetail(
      metadata,
      "Source-element IDs",
      chunk.sourceElementIds.join(", ")
    );
    card.append(metadata);

    const textHeading = document.createElement("h4");
    textHeading.textContent = "Complete chunk text";
    card.append(textHeading);

    const fullText = document.createElement("pre");
    fullText.textContent = chunk.text;
    fullText.style.whiteSpace = "pre-wrap";
    fullText.style.overflowWrap = "anywhere";
    card.append(fullText);

    container.append(card);
  });
}

async function searchRetrieval(event) {
  event.preventDefault();

  const queryInput = document.querySelector("#retrieval-query");
  const topKInput = document.querySelector("#retrieval-top-k");
  const button = document.querySelector("#retrieval-submit");
  const message = document.querySelector("#retrieval-message");
  const results = document.querySelector("#retrieval-results");

  const query = queryInput.value.trim();
  const topK = Number(topKInput.value);

  if (!query) {
    queryInput.setCustomValidity("Enter a question.");
    queryInput.reportValidity();
    queryInput.setCustomValidity("");
    return;
  }

  if (!topKInput.checkValidity() || !Number.isInteger(topK)) {
    topKInput.reportValidity();
    return;
  }

  button.disabled = true;
  results.replaceChildren();
  message.textContent = "Searching…";

  try {
    const httpResponse = await fetch("/api/retrieval/search", {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ query, topK })
    });

    if (!httpResponse.ok) {
      let detail;

      try {
        const problem = await httpResponse.json();
        detail = problem.detail;
      } catch {
        // Some server errors have no JSON response body.
      }

      throw new Error(detail || `HTTP ${httpResponse.status}`);
    }

    const retrieval = await httpResponse.json();
    renderRetrievalResults(retrieval);

    message.textContent = retrieval.resultCount === 0
      ? "No matching chunks were returned."
      : `${retrieval.resultCount} matching chunk` +
        `${retrieval.resultCount === 1 ? "" : "s"} returned.`;
  } catch (error) {
    message.textContent = `Search failed: ${error.message}`;
  } finally {
    button.disabled = false;
  }
}

document.querySelector("#begin-indexing").disabled = false;
document
  .querySelector("#begin-indexing")
  .addEventListener("click", beginIndexing);

document.querySelector("#retrieval-query").disabled = false;
document.querySelector("#retrieval-top-k").disabled = false;
document.querySelector("#retrieval-submit").disabled = false;
document
  .querySelector("#retrieval-form")
  .addEventListener("submit", searchRetrieval);

loadDocuments();
loadCorpusStatus();
resumeSavedJob();
