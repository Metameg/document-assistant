import {initializeCorpora} from "./js/corpora.js?v=corpus-deletion-5";
import {initializeAssistant} from "./js/assistant.js?v=corpus-deletion-5";
import {initializeDocuments} from "./js/documents.js?v=corpus-deletion-5";
import {initializeEvaluation} from "./js/evaluation.js?v=corpus-deletion-5";
import {initializeIndexing} from "./js/indexing.js";
import {initializePlotFallbacks} from "./js/plots.js";

await initializeCorpora();
initializeDocuments();
initializeIndexing();
initializeAssistant();
initializePlotFallbacks();
initializeEvaluation();

