import {getJson} from "./api.js";

const savedJobKey = "document-assistant-indexing-job";
let indexingEvents = null;

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
        throw new Error(
          problem.detail || "An indexing job is already running."
        );
      }

      message.textContent =
        "Connecting to the indexing job already running…";

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
    message.textContent =
      `Could not start indexing: ${error.message}`;
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
  message.textContent =
    "Checking the previous indexing job…";

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

export function initializeIndexing() {
  const button = document.querySelector("#begin-indexing");
  button.disabled = false;
  button.addEventListener("click", beginIndexing);
  loadCorpusStatus();
  resumeSavedJob();
}

