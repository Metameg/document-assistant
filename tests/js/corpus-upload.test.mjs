import test from "node:test";
import assert from "node:assert/strict";
import {describeUpload, prepareAndStartIndex} from "../../src/main/resources/static/js/corpus-upload.js";

test("a selected third paper finishes uploading before the index snapshot starts", async () => {
  let finishUpload;
  let count = 2;
  const calls = [];
  const pending = prepareAndStartIndex({files: [{name: "third.pdf"}],
    upload: files => {calls.push(["upload", files[0].name]); return new Promise(resolve => {finishUpload = resolve;});},
    afterUpload: async result => {count = result.documentCount; calls.push(["registered", count]);},
    needsRebuild: () => true,
    start: async () => {calls.push(["index", count]); return {total: count};}
  });
  assert.deepEqual(calls, [["upload", "third.pdf"]]);
  finishUpload({uploaded: [{sourceFile: "third.pdf"}], duplicates: [], documentCount: 3});
  const result = await pending;
  assert.deepEqual(calls, [["upload", "third.pdf"], ["registered", 3], ["index", 3]]);
  assert.equal(result.job.total, 3);
});

test("a failed upload never starts a rebuild of only the old documents", async () => {
  let started = false;
  await assert.rejects(prepareAndStartIndex({files: [{}],
    upload: async () => {throw new Error("Upload failed");}, afterUpload: async () => {},
    needsRebuild: () => false, start: async () => {started = true;}
  }), /Upload failed/);
  assert.equal(started, false);
});

test("an already-indexed duplicate is explained and does not trigger embedding again", async () => {
  let started = false;
  const duplicate = {uploaded: [], duplicates: [{sourceFile: "paper-copy.pdf", existingSourceFile: "paper.pdf"}], documentCount: 2};
  const result = await prepareAndStartIndex({files: [{}], upload: async () => duplicate,
    afterUpload: async () => {}, needsRebuild: () => false, start: async () => {started = true;}
  });
  assert.equal(started, false);
  assert.equal(result.job, null);
  assert.match(describeUpload(duplicate), /No new PDFs added/);
  assert.match(describeUpload(duplicate), /Corpus contains 2 documents/);
  assert.match(describeUpload(duplicate), /paper-copy.pdf \(stored as paper.pdf\)/);
});

test("duplicate selection still indexes other previously uploaded pending papers", async () => {
  let started = false;
  await prepareAndStartIndex({files: [{}], upload: async () => ({uploaded: [], duplicates: [{}], documentCount: 3}),
    afterUpload: async () => {}, needsRebuild: () => true, start: async () => {started = true; return {total: 3};}
  });
  assert.equal(started, true);
});

test("a rebuild with no file selected uses the registered corpus", async () => {
  const result = await prepareAndStartIndex({files: [], upload: () => assert.fail("Unexpected upload"),
    afterUpload: () => assert.fail("Unexpected upload refresh"), needsRebuild: () => false,
    start: async () => ({total: 3})
  });
  assert.equal(result.job.total, 3);
});
