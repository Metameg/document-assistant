import {getJson} from "./api.js";
import {createJobMonitor, hasActiveJobs} from "./corpus-jobs.js";
import {describeUpload, prepareAndStartIndex} from "./corpus-upload.js";

let selected = {id: "specifications", name: "Generac specifications", profile: "specifications"};
let corpora = [];
let benchmarks = [];
let benchmark = null;
let sourceChunks = {};
let settings = {maxK: 40, defaultKValues: [5, 10, 20, 30, 40]};
let activeJob = null;
let ingestionBusy = false;
let pendingRemoval = null;
let epoch = 0;
let loadingBenchmark = 0;
let loadingReport = 0;
const $ = id => document.getElementById(id);

export const currentCorpus = () => selected;
export const corpusBusy = () => !!activeJob || ingestionBusy;
export const corpusUrl = (suffix = "", id = selected.id) => `/api/corpora/${encodeURIComponent(id)}${suffix}`;

async function request(url, options = {}) {
  const response = await fetch(url, options);
  if (!response.ok) {
    let detail;
    try {detail = (await response.json()).detail;} catch { /* non-JSON response */ }
    throw new Error(detail || `HTTP ${response.status}`);
  }
  return response.json();
}
const post = (path, data = {}, id = selected.id) => request(corpusUrl(path, id), {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(data)});
function message(id, text, error = false) {$(id).textContent = text; $(id).classList.toggle("error", error);}
function element(tag, text, className) {const node = document.createElement(tag); if (text !== undefined) node.textContent = text; if (className) node.className = className; return node;}

async function refreshCorpora() {
  corpora = await getJson("/api/corpora");
  const picker = $("active-corpus"); picker.replaceChildren();
  for (const corpus of corpora) {const option = element("option", corpus.name); option.value = corpus.id; picker.append(option);}
  selected = corpora.find(c => c.id === selected.id) || corpora[0];
  picker.value = selected.id; picker.disabled = ingestionBusy;
  const state = selected.profile === "specifications" ? `${selected.documentCount} documents · ${selected.chunkCount} chunks`
    : !selected.activeVersion ? `${selected.documentCount} papers uploaded · Not indexed`
    : `${selected.documentCount} uploaded papers · ${selected.chunkCount} indexed chunks${selected.pendingChanges ? " · Uploaded changes need processing" : " · Ready for questions"}`;
  message("active-corpus-status", state);
}

function busy(job) {
  activeJob = job && !job.terminal ? job : null;
  for (const node of document.querySelectorAll("#upload-papers-form input, #upload-papers-form button, #generate-benchmark-form input, #generate-benchmark-form button, #active-corpus")) node.disabled = !!activeJob || ingestionBusy;
  // Corpus selection remains available while a backend job is running, but not mid-upload.
  $("active-corpus").disabled = ingestionBusy;
  for (const node of document.querySelectorAll("#create-corpus-form input, #create-corpus-form button")) node.disabled = ingestionBusy;
  $("process-papers").disabled = !!activeJob || ingestionBusy || (!selected.documentCount && !$("paper-files").files.length);
  $("generate-benchmark-form").querySelector("button").disabled = !!activeJob || ingestionBusy || !selected.activeVersion;
  $("approve-benchmark").disabled = !!activeJob || ingestionBusy || !benchmark || benchmark.versionId !== selected.activeVersion;
  for (const button of document.querySelectorAll(".remove-document, #delete-corpus")) button.disabled = corpusBusy();
  for (const button of document.querySelectorAll("#version-history button, #retry-corpus-job")) button.disabled = corpusBusy();
  $("benchmark-review-form").querySelector('button[type="submit"]').disabled = corpusBusy() || !benchmark;
  updateWorkload();
}

function showRemoval(document = null) {
  if (corpusBusy() || selected.profile !== "research") return;
  pendingRemoval = {corpusId: selected.id, documentId: document?.documentId, name: document?.sourceFile || selected.name};
  const entire = !document;
  $("delete-title").textContent = entire ? "Delete corpus?" : "Remove document?";
  $("delete-description").textContent = entire
    ? `Permanently delete “${selected.name}”, its ${selected.documentCount} papers, all indexes, benchmarks, evaluation results, and local files. This cannot be undone.`
    : `Permanently remove “${document.sourceFile}” and its text from this corpus. Older indexes containing this paper, and benchmarks and evaluations tied to those indexes, will also be deleted. Other papers remain searchable. This cannot be undone.`;
  $("delete-name-label").hidden = !entire;
  $("delete-name").disabled = !entire; $("delete-name").required = entire; $("delete-name").value = "";
  $("delete-name-prompt").textContent = `Type ${selected.name} to confirm`;
  $("confirm-delete").textContent = entire ? "Delete corpus" : "Remove document";
  $("confirm-delete").disabled = entire;
  message("delete-message", ""); $("deletion-dialog").showModal();
}

export function requestDocumentRemoval(document) {showRemoval(document);}

async function confirmRemoval(event) {
  event.preventDefault();
  const target = pendingRemoval;
  if (!target || target.corpusId !== selected.id || corpusBusy()) return;
  if (!target.documentId && $("delete-name").value !== target.name) return;
  ingestionBusy = true; busy(activeJob); jobMonitor.stop(); epoch++;
  $("confirm-delete").disabled = true; $("cancel-delete").disabled = true;
  message("delete-message", "Removing…");
  let committed = false;
  try {
    const path = target.documentId ? `/documents/${encodeURIComponent(target.documentId)}` : "";
    const result = await request(corpusUrl(path, target.corpusId), {method: "DELETE"});
    committed = true; $("deletion-dialog").close();
    await refreshCorpora(); await activate();
    let text = target.documentId ? `Removed ${target.name}.` : `Deleted corpus ${target.name}.`;
    if (target.documentId && result.removedVersions) text += ` Removed ${result.removedVersions} historical index(es), ${result.removedBenchmarks} benchmark(s), and ${result.removedEvaluations} evaluation run(s) tied to the paper.`;
    if (result.cleanupPending) text += " Local file cleanup is pending and will retry automatically.";
    message("corpus-management-message", text);
  } catch (error) {
    if (committed) message("corpus-management-message", `Removal completed, but the page could not refresh: ${error.message}. Reload the page.`, true);
    else message("delete-message", `Could not remove: ${error.message}`, true);
  } finally {
    ingestionBusy = false; busy(activeJob); $("cancel-delete").disabled = false;
    $("confirm-delete").disabled = !target.documentId && $("delete-name").value !== target.name;
    if (committed) pendingRemoval = null;
  }
}

function renderJob(job) {
  busy(job);
  const progress = $("research-progress"); progress.hidden = job.terminal; progress.max = Math.max(job.total, 1); progress.value = job.completed;
  const text = `${job.stage.replaceAll("_", " ").toLowerCase()}: ${job.message}`;
  message("research-processing-message", text, ["FAILED", "INTERRUPTED"].includes(job.stage));
  const units = job.kind === "INDEX" ? "documents" : job.kind === "BENCHMARK" ? "questions" : "answer evaluations";
  const chunks = job.chunks ? `${job.chunks} chunks ${job.stage === "COMPLETED" ? "indexed" : "generated"}` : "";
  message("research-job-detail", [job.currentDocument, `${job.completed}/${job.total} ${units}`, chunks].filter(Boolean).join(" · "));
  if (job.kind !== "INDEX") message("research-evaluation-message", text, ["FAILED", "INTERRUPTED"].includes(job.stage));
  $("retry-corpus-job").hidden = !["FAILED", "INTERRUPTED"].includes(job.stage);
  $("retry-corpus-job").dataset.jobId = job.jobId;
}

const jobMonitor = createJobMonitor({
  read: (job, id) => getJson(corpusUrl(`/jobs/${job.jobId}`, id)),
  render: renderJob,
  complete: async (status, id, isCurrent) => {
    await refreshCorpora();
    if (!isCurrent() || id !== selected.id) return;
    window.dispatchEvent(new Event("corpusupdated"));
    if (status.kind === "BENCHMARK") $("benchmark-select").value = "";
    await loadResearch();
  },
  error: (failure, phase) => {
    if (phase === "read") {
      message("research-job-detail", `Could not read progress: ${failure.message}. Retrying…`, true);
    } else {
      message("research-job-detail", `The job finished, but the page could not refresh: ${failure.message}. Reload the page to update the display.`, true);
    }
  }
});

function watch(job) {jobMonitor.watch(job, selected.id);}

async function uploadSelected(files, id) {
  const data = new FormData();
  for (const file of files) data.append("files", file);
  message("upload-message", `Uploading ${files.length} PDF${files.length === 1 ? "" : "s"}…`);
  return request(corpusUrl("/documents", id), {method: "POST", body: data});
}

async function showUploaded(result) {
  $("paper-files").value = "";
  await refreshCorpora();
  window.dispatchEvent(new Event("corpusupdated"));
  message("upload-message", describeUpload(result));
}

async function loadResearch() {
  if (selected.profile !== "research") return;
  const id = selected.id, expected = epoch;
  const [jobs, drafts, runs, versions] = await Promise.all([
    getJson(corpusUrl("/jobs", id)), getJson(corpusUrl("/benchmarks", id)), getJson(corpusUrl("/evaluation-runs", id)), getJson(corpusUrl("/versions", id))
  ]);
  if (expected !== epoch) return;
  benchmarks = drafts;
  const previous = $("benchmark-select").value;
  $("benchmark-select").replaceChildren();
  for (const draft of drafts) {
    const stale = draft.versionId !== selected.activeVersion;
    const option = element("option", `${new Date(draft.createdAt).toLocaleString()} · ${draft.state.toLowerCase()}${stale ? " · older index" : ""} · ${draft.dataset.length} questions`);
    option.value = draft.id; $("benchmark-select").append(option);
  }
  if (drafts.some(d => d.id === previous)) $("benchmark-select").value = previous;
  await loadBenchmark();
  const history = $("evaluation-history"), priorRun = history.value; history.replaceChildren();
  for (const run of runs) {const option = element("option", `${new Date(run.job.startedAt).toLocaleString()} · ${run.job.stage.toLowerCase()} · k=${run.configuration.kValues.join(", ")}${run.configuration.versionId !== selected.activeVersion ? " · older index" : ""}`); option.value = run.job.jobId; history.append(option);}
  if (runs.some(r => r.job.jobId === priorRun)) history.value = priorRun;
  $("version-history").replaceChildren();
  for (const version of versions) {
    const li = element("li", `${new Date(version.createdAt).toLocaleString()} · ${version.documents.length} papers · ${version.chunkCount} chunks`);
    if (version.id === selected.activeVersion) li.append(element("strong", " · Active"));
    else {const button = element("button", "Use this index", "secondary"); button.type = "button"; button.disabled = hasActiveJobs(jobs); button.onclick = async () => {
      try {await post(`/versions/${version.id}/activate`); await refreshCorpora(); window.dispatchEvent(new Event("corpuschange")); await loadResearch();}
      catch (e) {message("research-processing-message", e.message, true);}
    }; li.append(button);}
    $("version-history").append(li);
  }
  if (jobs.length) watch(jobs.find(j => !j.terminal) || jobs[0]);
  else {busy(null); message("research-processing-message", selected.activeVersion ? "Index ready for questions." : "Upload PDFs, then process and index the collection."); $("retry-corpus-job").hidden = true;}
  await loadReport(); updateWorkload();
}

function field(parent, label, value, name, rows = 3) {
  const wrapper = element("label", label); const input = document.createElement("textarea"); input.value = value; input.rows = rows; input.dataset.field = name; input.required = true; wrapper.append(input); parent.append(wrapper);
}

function appendSource(container, key, chunk, checked) {
  if (container.querySelectorAll("input").length && [...container.querySelectorAll("input")].some(i => i.value === key)) return;
  const detail = document.createElement("details"), summary = document.createElement("summary");
  const checkbox = document.createElement("input"); checkbox.type = "checkbox"; checkbox.value = key; checkbox.checked = checked;
  checkbox.setAttribute("aria-label", `Use source ${chunk.sourceFile}, pages ${chunk.pageNumbers.join(", ")}`);
  checkbox.addEventListener("click", event => event.stopPropagation());
  summary.append(checkbox, document.createTextNode(` ${chunk.sourceFile} · pages ${chunk.pageNumbers.join(", ")} · ${chunk.section}`));
  const link = element("a", "Open source PDF ↗"); link.target = "_blank"; link.rel = "noopener";
  link.href = corpusUrl(`/documents/${encodeURIComponent(chunk.documentId)}/pdf`) + `#page=${chunk.pageNumbers[0]}`;
  detail.append(summary, link, element("pre", chunk.text)); container.append(detail);
}

async function loadBenchmark() {
  const token = ++loadingBenchmark, id = selected.id;
  benchmark = benchmarks.find(b => b.id === $("benchmark-select").value) || null;
  $("benchmark-editor").replaceChildren(); $("benchmark-review-actions").hidden = !benchmark;
  if (!benchmark) {message("benchmark-state", "Generate a benchmark after indexing your papers."); updateWorkload(); return;}
  const current = benchmark;
  const stale = current.versionId !== selected.activeVersion;
  message("benchmark-state", `${current.state.toLowerCase()}${stale ? " · This benchmark needs review against the active index." : " · References apply to the active index."}`);
  try {
    sourceChunks = await getJson(corpusUrl(`/benchmarks/${current.id}/sources`, id));
    if (id !== selected.id || token !== loadingBenchmark) return;
    for (const [index, question] of current.dataset.entries()) {
      const section = element("fieldset", undefined, "benchmark-question"); section.dataset.questionId = question.id;
      section.append(element("legend", `Question ${index + 1}`));
      field(section, "Question", question.question, "question"); field(section, "Reference answer", question.referenceAnswer, "referenceAnswer"); field(section, "Expected claims (one per line)", question.requiredClaims.join("\n"), "requiredClaims");
      const sources = element("div", undefined, "benchmark-sources");
      section.append(element("p", "Supporting passages · check the passages that establish the reference answer.", "subtle"), sources);
      for (const key of question.sourceChunkIds) if (sourceChunks[key]) appendSource(sources, key, sourceChunks[key], true);
      const search = element("button", "Find supporting passages", "secondary"); search.type = "button";
      search.disabled = stale;
      search.onclick = async () => {
        search.disabled = true;
        try {
          const response = await request("/api/retrieval/search", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({query: section.querySelector('[data-field="question"]').value, topK: 5, corpusId: id, versionId: current.versionId})});
          for (const chunk of response.results) {const key = `${chunk.documentId}:${chunk.chunkIndex}`; sourceChunks[key] = chunk; appendSource(sources, key, chunk, false);}
        } catch (e) {message("benchmark-state", e.message, true);} finally {search.disabled = false;}
      };
      const wrapper = element("details", undefined, "benchmark-review-question"); wrapper.open = index === 0;
      wrapper.append(element("summary", `Question ${index + 1} · ${question.question}`), section);
      const remove = element("button", "Remove question", "secondary"); remove.type = "button"; remove.onclick = () => wrapper.remove();
      const actions = element("div", undefined, "button-group"); actions.append(search, remove); section.append(actions); $("benchmark-editor").append(wrapper);
    }
    busy(activeJob);
  } catch (e) {message("benchmark-state", e.message, true);}
  updateWorkload();
}

function reviewedDataset() {
  const originals = new Map(benchmark.dataset.map(q => [q.id, q]));
  return [...$("benchmark-editor").querySelectorAll("fieldset")].map(section => ({...originals.get(section.dataset.questionId),
    question: section.querySelector('[data-field="question"]').value.trim(), referenceAnswer: section.querySelector('[data-field="referenceAnswer"]').value.trim(),
    requiredClaims: section.querySelector('[data-field="requiredClaims"]').value.split("\n").map(v => v.trim()).filter(Boolean),
    sourceChunkIds: [...section.querySelectorAll('.benchmark-sources input:checked')].map(i => i.value)}));
}

async function saveReview() {
  const saved = await post(`/benchmarks/${benchmark.id}/revisions`, {dataset: reviewedDataset()});
  benchmarks.unshift(saved); const option = element("option", "New reviewed draft"); option.value = saved.id; $("benchmark-select").prepend(option); $("benchmark-select").value = saved.id;
  await loadBenchmark(); return saved;
}

function ks() {
  const parts = $("research-k-values").value.split(/[,\s]+/).filter(Boolean);
  if (!parts.length || parts.length > 100 || parts.some(v => !/^[1-9]\d*$/.test(v) || Number(v) > settings.maxK)) throw new Error(`Use evidence limits from 1 to ${settings.maxK}.`);
  return [...new Set(parts.map(Number))].sort((a, b) => a - b);
}

function updateWorkload() {
  message("benchmark-workload", `Draft generation: approximately ${$("benchmark-count").value || 20} model calls, plus retries. Judge: ${settings.judgeModel || "configured model"}.`);
  let valid = false;
  try {
    const values = ks(), count = benchmark?.dataset.length || 0;
    message("research-evaluation-workload", `${count} questions × ${values.length} evidence limits = ${count * values.length} answer calls and ${count * values.length} judge calls, plus relevance-labeling calls and retries. Answer: ${settings.answerModel || "configured model"}. Pool size determines labeling cost.`);
    valid = !!benchmark && benchmark.state === "APPROVED" && benchmark.versionId === selected.activeVersion;
  } catch (e) {message("research-evaluation-workload", e.message, true);}
  $("run-research-evaluation").disabled = corpusBusy() || !valid;
}

async function loadReport() {
  const run = $("evaluation-history").value, id = selected.id, expected = epoch, reportToken = ++loadingReport;
  $("research-report").replaceChildren(); $("research-plots").replaceChildren();
  if (!run) return;
  const link = element("a", "Open full JSON report ↗"); link.href = corpusUrl(`/evaluation-runs/${run}/report`); link.target = "_blank"; link.rel = "noopener"; $("research-report").append(link);
  try {
    const report = await getJson(corpusUrl(`/evaluation-runs/${run}/report`));
    if (id !== selected.id || expected !== epoch || reportToken !== loadingReport) return;
    for (const result of report.results || []) {
      const details = document.createElement("details"); details.append(element("summary", `${result.question} · k=${result.k}${result.error ? " · failed" : ""}`));
      details.append(element("pre", result.response?.answer || result.error || "Not completed"));
      if (result.judgment) details.append(element("p", `Judge: ${result.judgment.reason}`));
      if (result.metrics) details.append(element("p", `Correctness precision: ${result.metrics.correctness.precision ?? "n/a"} · recall: ${result.metrics.correctness.recall ?? "n/a"}`));
      for (const item of result.response?.evidence || []) {
        const source = element("details"); source.append(element("summary", `[${item.sourceNumber}] ${item.candidate.sourceFile} · ${item.candidate.section}`), element("pre", item.candidate.text)); details.append(source);
      }
      $("research-report").append(details);
    }
    const plots = {"answer-correctness.png": "Answer correctness", "retrieval-ranking.png": "Retrieval precision and pool recall", "retrieval-map-mrr.png": "Retrieval mAP and MRR", "retrieval-latency.png": "Retrieval latency", "inference-latency.png": "Model inference latency"};
    for (const [file, title] of Object.entries(plots)) {
      const figure = element("figure", undefined, "panel plot-card"), caption = element("figcaption", title), image = document.createElement("img"), anchor = element("a");
      image.alt = title; image.src = corpusUrl(`/evaluation-runs/${run}/plots/${file}`); anchor.href = image.src; anchor.target = "_blank"; anchor.rel = "noopener"; anchor.append(image);
      image.onerror = () => {anchor.hidden = true; figure.append(element("p", "Chart is not available yet.", "subtle"));}; figure.append(caption, anchor); $("research-plots").append(figure);
    }
  } catch (e) {if (expected === epoch) $("research-report").append(element("p", "Report will appear when answer evaluation starts.", "subtle"));}
}

async function activate() {
  epoch++; jobMonitor.stop(); activeJob = null; benchmark = null; benchmarks = []; sourceChunks = {};
  const research = selected.profile === "research";
  $("research-ingestion").hidden = !research; $("research-evaluation").hidden = !research; $("legacy-indexing").hidden = research; $("legacy-evaluation").hidden = research;
  $("benchmark-select").replaceChildren(); $("benchmark-editor").replaceChildren(); $("research-report").replaceChildren(); $("research-plots").replaceChildren();
  $("paper-files").value = ""; $("research-progress").hidden = true; message("upload-message", "");
  localStorage.setItem("document-assistant-corpus", selected.id);
  window.dispatchEvent(new Event("corpuschange"));
  if (research) try {await loadResearch();} catch (e) {message("research-processing-message", e.message, true);}
}

export async function initializeCorpora() {
  selected.id = localStorage.getItem("document-assistant-corpus") || selected.id;
  try {await refreshCorpora(); settings = await getJson("/api/corpora/evaluation-settings"); $("research-k-values").value = settings.defaultKValues.join(", ");}
  catch (e) {message("active-corpus-status", `Could not load corpora: ${e.message}`, true);}
  $("delete-corpus").onclick = () => showRemoval();
  $("delete-form").onsubmit = confirmRemoval;
  $("delete-name").oninput = () => {$("confirm-delete").disabled = $("delete-name").value !== pendingRemoval?.name;};
  $("cancel-delete").onclick = () => {$("deletion-dialog").close(); pendingRemoval = null;};
  $("deletion-dialog").addEventListener("cancel", event => {if (ingestionBusy) event.preventDefault(); else pendingRemoval = null;});
  $("active-corpus").addEventListener("change", async () => {selected = corpora.find(c => c.id === $("active-corpus").value); await refreshCorpora(); await activate();});
  $("create-corpus-form").addEventListener("submit", async event => {
    event.preventDefault(); const button = event.target.querySelector("button"); button.disabled = true;
    try {const created = await request("/api/corpora", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({name: $("corpus-name").value.trim()})}); selected = created; await refreshCorpora(); await activate(); $("corpus-name").value = ""; message("create-corpus-message", "Corpus created. Upload your papers below.");}
    catch (e) {message("create-corpus-message", e.message, true);} finally {button.disabled = false;}
  });
  $("upload-papers-form").addEventListener("submit", async event => {
    event.preventDefault(); if (ingestionBusy || activeJob) return;
    const files = [...$("paper-files").files], id = selected.id;
    ingestionBusy = true; busy(activeJob);
    try {
      const result = await uploadSelected(files, id);
      await showUploaded(result); await loadResearch();
      if (selected.pendingChanges) message("upload-message", describeUpload(result) + " Press Process and index to make the uploaded collection searchable.");
    } catch (e) {message("upload-message", `Upload failed: ${e.message}. The existing index is unchanged.`, true);}
    finally {ingestionBusy = false; busy(activeJob);}
  });
  $("paper-files").onchange = () => {
    busy(activeJob);
    if ($("paper-files").files.length) message("upload-message", `${$("paper-files").files.length} PDF(s) selected locally. Process and index will upload them first.`);
    else message("upload-message", "");
  };
  $("process-papers").onclick = async () => {
    if (ingestionBusy || activeJob) return;
    const files = [...$("paper-files").files], id = selected.id;
    ingestionBusy = true; busy(activeJob);
    try {
      const result = await prepareAndStartIndex({files, upload: selectedFiles => uploadSelected(selectedFiles, id),
        afterUpload: showUploaded, needsRebuild: () => selected.pendingChanges,
        start: () => post("/processing-jobs", {}, id)});
      if (result.job) watch(result.job);
      else message("research-processing-message", "The selected PDFs are already indexed. No rebuild was needed.");
    } catch (e) {message("research-processing-message", `Could not start indexing: ${e.message}`, true);}
    finally {ingestionBusy = false; busy(activeJob);}
  };
  $("retry-corpus-job").onclick = async () => {try {watch(await post(`/jobs/${$("retry-corpus-job").dataset.jobId}/retry`));} catch (e) {message("research-processing-message", e.message, true);}};
  $("generate-benchmark-form").onsubmit = async event => {event.preventDefault(); try {watch(await post("/benchmarks/generate", {count: Number($("benchmark-count").value)}));} catch (e) {message("research-evaluation-message", e.message, true);}};
  $("benchmark-count").oninput = updateWorkload; $("research-k-values").oninput = updateWorkload;
  $("benchmark-select").onchange = loadBenchmark;
  $("benchmark-review-form").onsubmit = async event => {event.preventDefault(); try {await saveReview(); message("benchmark-state", "Reviewed draft saved. Approve it to enable evaluation.");} catch (e) {message("benchmark-state", e.message, true);}};
  $("approve-benchmark").onclick = async () => {try {if (JSON.stringify(reviewedDataset()) !== JSON.stringify(benchmark.dataset)) await saveReview(); await post(`/benchmarks/${benchmark.id}/approve`); await loadResearch(); message("benchmark-state", "Benchmark approved. Evaluation is ready.");} catch (e) {message("benchmark-state", e.message, true);}};
  $("run-research-evaluation").onclick = async () => {try {watch(await post("/evaluation-runs", {benchmarkId: benchmark.id, kValues: ks()}));} catch (e) {message("research-evaluation-message", e.message, true);}};
  $("evaluation-history").onchange = loadReport;
  await activate();
}
