import {initializeAssistant} from "./js/assistant.js";
import {initializeDocuments} from "./js/documents.js";
import {initializeEvaluation} from "./js/evaluation.js";
import {initializeIndexing} from "./js/indexing.js";
import {initializePlotFallbacks} from "./js/plots.js";

initializeDocuments();
initializeIndexing();
initializeAssistant();
initializePlotFallbacks();
initializeEvaluation();

