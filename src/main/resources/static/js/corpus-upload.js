export function describeUpload(result) {
  const added = result.uploaded.length;
  let text = added ? `${added} new PDF${added === 1 ? "" : "s"} uploaded.` : "No new PDFs added.";
  text += ` Corpus contains ${result.documentCount} document${result.documentCount === 1 ? "" : "s"}.`;
  if (result.duplicates.length) {
    text += " Identical content already in this corpus: " + result.duplicates.map(file =>
      file.sourceFile === file.existingSourceFile ? file.sourceFile : `${file.sourceFile} (stored as ${file.existingSourceFile})`).join("; ") + ".";
  }
  return text;
}

// The index snapshot must be taken after every selected file has been registered.
export async function prepareAndStartIndex({files, upload, afterUpload, needsRebuild, start}) {
  let uploadResult = null;
  if (files.length) {
    uploadResult = await upload(files);
    await afterUpload(uploadResult);
    if (!uploadResult.uploaded.length && !needsRebuild()) return {job: null, uploadResult};
  }
  return {job: await start(), uploadResult};
}
