import {getJson} from "./api.js";

export function initializeEvaluation() {
  // Evaluation k values are validated in the browser and again by the server.
  const evaluationButton = document.querySelector("#run-evaluation");
  const evaluationMessage = document.querySelector("#evaluation-message");
  const evaluationKInput = document.querySelector("#evaluation-k-values");
  const evaluationKError = document.querySelector("#evaluation-k-error");
  let evaluationTimer = null;
  let evaluationStarting = false;
  let evaluationBusy = false;
  let evaluationStatusKnown = false;
  let evaluationRunKValues = null;
  
  const evaluationSettings = Object.freeze({
    defaultKValues: [5, 10, 20, 30, 40],
    maxK: 40,
    questionCount: 50,
    baseMinutesPerK: 18,
    minutesPerEvidenceChunk: 18 / 21,
    modelCallsPerCase: 2,
    usdPerModelCall: 0.002,
    costBaseK: 5,
    costReferenceK: 21
  });
  
  function parseEvaluationKValues(text, maxK) {
    const parts = text.trim().split(/[,\s]+/).filter(Boolean);
    if (!parts.length) throw new Error("Enter at least one evidence limit.");
    if (parts.length > 100) throw new Error("Enter no more than 100 values.");
    if (parts.some(part => !/^[1-9]\d*$/.test(part) || !Number.isSafeInteger(Number(part)) || Number(part) > maxK)) {
      throw new Error(`Each k must be a whole number from 1 to ${maxK}.`);
    }
    return [...new Set(parts.map(Number))].sort((a, b) => a - b);
  }
  
  function calculateEvaluationEstimate(settings, kValues) {
    const kSum = kValues.reduce((sum, k) => sum + k, 0);
    const minutes = settings.baseMinutesPerK * kValues.length
      + settings.minutesPerEvidenceChunk * kSum;
    const seconds = minutes * 60;
    const cases = settings.questionCount * kValues.length;
    const modelCalls = cases * settings.modelCallsPerCase;
    const costWeight = (kSum + settings.costBaseK * kValues.length)
      / (settings.costReferenceK + settings.costBaseK);
    const usd = settings.questionCount * settings.modelCallsPerCase
      * settings.usdPerModelCall * costWeight;
    return {seconds, usd, cases, modelCalls, kSum, costWeight};
  }
  
  function formatEvaluationDuration(seconds) {
    if (seconds < 60) return `${Math.ceil(seconds)} sec`;
    if (seconds < 3600) return `${(seconds / 60).toFixed(1)} min`;
    return `${(seconds / 3600).toFixed(1)} hr`;
  }
  
  function formatEvaluationCost(usd) {
    return new Intl.NumberFormat("en-US", {style: "currency", currency: "USD",
      minimumFractionDigits: usd < 1 ? 4 : 2, maximumFractionDigits: usd < 1 ? 4 : 2}).format(usd);
  }
  
  function updateEvaluationPlan() {
    let valid = false;
    if (evaluationSettings) {
      try {
        const ks = parseEvaluationKValues(evaluationKInput.value, evaluationSettings.maxK);
        const estimate = calculateEvaluationEstimate(evaluationSettings, ks);
        evaluationKError.hidden = true;
        evaluationKInput.removeAttribute("aria-invalid");
        document.querySelector("#evaluation-estimate").textContent =
          `Estimated runtime: ~${formatEvaluationDuration(estimate.seconds)} · Estimated model cost: ~${formatEvaluationCost(estimate.usd)}`;
        document.querySelector("#evaluation-estimate-basis").textContent =
          `Time: (${ks.length} k value${ks.length === 1 ? "" : "s"} × ~${evaluationSettings.baseMinutesPerK} fixed min) + (${estimate.kSum} total evidence slots × ~${evaluationSettings.minutesPerEvidenceChunk.toFixed(2)} min) = ~${formatEvaluationDuration(estimate.seconds)}. ` +
          `Cost: ${evaluationSettings.questionCount} questions × ${evaluationSettings.modelCallsPerCase} calls/question-k × ~${formatEvaluationCost(evaluationSettings.usdPerModelCall)}/call at k≈${evaluationSettings.costReferenceK}, weighted by the selected k values = ~${formatEvaluationCost(estimate.usd)}. ` +
          "Calibrated from the last full GPT-oss-120b run; actual time and cost vary with context length, retries and provider load. Relevance labeling is excluded.";
        valid = true;
      } catch (error) {
        evaluationKError.hidden = false;
        evaluationKError.textContent = error.message;
        evaluationKInput.setAttribute("aria-invalid", "true");
        document.querySelector("#evaluation-estimate").textContent = "Enter valid k values to see the estimate.";
        document.querySelector("#evaluation-estimate-basis").textContent = "";
      }
    }
    evaluationKInput.disabled = evaluationBusy || evaluationStarting || !evaluationSettings;
    evaluationButton.disabled = evaluationBusy || evaluationStarting || !evaluationStatusKnown
      || !valid;
  }
  
  function initializeEvaluationSettings() {
    evaluationKInput.value = evaluationSettings.defaultKValues.join(", ");
    document.querySelector("#evaluation-k-help").textContent =
      `Enter comma-separated whole numbers from 1 to ${evaluationSettings.maxK}. Each distinct value runs the entire question suite.`;
    updateEvaluationPlan();
  }
  
  evaluationKInput.addEventListener("input", updateEvaluationPlan);
  evaluationKInput.addEventListener("blur", () => {
    if (!evaluationSettings) return;
    try { evaluationKInput.value = parseEvaluationKValues(evaluationKInput.value, evaluationSettings.maxK).join(", "); }
    catch { /* Preserve invalid input so the user can correct it. */ }
    updateEvaluationPlan();
  });
  
  function refreshEvaluationPlots(version) {
    for (const img of document.querySelectorAll(".plot-card img")) {
      const url = new URL(img.src);
      url.searchParams.set("v", version);
      img.closest("figure").querySelector("a").href = url.href;
      img.src = url.href;
    }
  }
  
  function showEvaluationStatus(status) {
    evaluationStatusKnown = true;
    evaluationBusy = !status.terminal;
    evaluationRunKValues = status.kValues?.length ? status.kValues : null;
    if (evaluationBusy && evaluationRunKValues) evaluationKInput.value = evaluationRunKValues.join(", ");
    updateEvaluationPlan();
    evaluationMessage.classList.toggle("busy", evaluationBusy);
    evaluationMessage.classList.toggle("error", status.stage === "FAILED");
    evaluationMessage.classList.toggle("complete", status.stage === "COMPLETED");
    evaluationMessage.textContent = status.message;
    if (status.kValues?.length) evaluationMessage.textContent += ` Tested k: ${status.kValues.join(", ")}.`;
    if (status.terminal && status.startedAt && status.finishedAt) {
      const seconds = (Date.parse(status.finishedAt) - Date.parse(status.startedAt)) / 1000;
      if (Number.isFinite(seconds)) evaluationMessage.textContent += ` Elapsed: ${Math.round(seconds)} seconds.`;
    }
    if (status.stage === "COMPLETED") refreshEvaluationPlots(status.jobId);
    if (evaluationBusy) evaluationTimer = setTimeout(() => pollEvaluation(status.jobId), 3000);
  }
  
  async function pollEvaluation(jobId) {
    clearTimeout(evaluationTimer);
    try {
      showEvaluationStatus(await getJson(`/api/demo/evaluation-jobs/${encodeURIComponent(jobId)}`));
    } catch (error) {
      if (error.status === 404) {
        evaluationStatusKnown = true;
        evaluationBusy = false;
        updateEvaluationPlan();
        evaluationMessage.classList.remove("busy", "complete");
        evaluationMessage.classList.add("error");
        evaluationMessage.textContent = "This evaluation job is no longer available, possibly because the application restarted. Check its report before starting another run.";
      } else {
        evaluationMessage.textContent = "Could not read evaluation progress. Retrying…";
        evaluationTimer = setTimeout(() => pollEvaluation(jobId), 3000);
      }
    }
  }
  
  async function resumeEvaluation() {
    clearTimeout(evaluationTimer);
    try {
      const response = await fetch("/api/demo/evaluation-jobs/current", {cache: "no-store"});
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      if (response.status === 204) {
        evaluationStatusKnown = true;
        evaluationBusy = false;
        updateEvaluationPlan();
        evaluationMessage.classList.remove("busy", "error", "complete");
        evaluationMessage.textContent = "Ready to run the evaluation.";
      } else showEvaluationStatus(await response.json());
    } catch (error) {
      // Keep launch disabled if the running-job status is unknown.
      evaluationStatusKnown = false;
      updateEvaluationPlan();
      evaluationMessage.classList.remove("busy", "complete");
      evaluationMessage.classList.add("error");
      evaluationMessage.textContent = `Could not check evaluation status: ${error.message}. Reload to retry.`;
    }
  }
  
  evaluationButton.addEventListener("click", async () => {
    if (evaluationStarting || evaluationButton.disabled) return;
    const kValues = parseEvaluationKValues(evaluationKInput.value, evaluationSettings.maxK);
    evaluationStarting = true;
    updateEvaluationPlan();
    evaluationMessage.classList.remove("error", "complete");
    evaluationMessage.classList.add("busy");
    evaluationMessage.textContent = "Starting evaluation…";
    try {
      const response = await fetch("/api/demo/evaluation-jobs", {method: "POST",
        headers: {"Content-Type": "application/json"}, body: JSON.stringify({kValues})});
      if (response.status === 409) { await resumeEvaluation(); return; }
      if (!response.ok) {
        let detail;
        try { detail = (await response.json()).detail; } catch { /* Empty or non-JSON error. */ }
        throw new Error(detail || `HTTP ${response.status}`);
      }
      showEvaluationStatus(await response.json());
    } catch (error) {
      // A lost POST response may still have started a job; check before enabling retry.
      await resumeEvaluation();
      if (!evaluationBusy && evaluationStatusKnown) {
        evaluationMessage.classList.remove("busy", "complete");
        evaluationMessage.classList.add("error");
        evaluationMessage.textContent = `Could not start evaluation: ${error.message}.`;
      }
    } finally {
      evaluationStarting = false;
      updateEvaluationPlan();
    }
  });
  initializeEvaluationSettings();
  resumeEvaluation();
}

