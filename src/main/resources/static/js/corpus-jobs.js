export const hasActiveJobs = jobs => jobs.some(job => !job.terminal);

// A completed backend job stays completed even if refreshing another UI panel fails.
export function createJobMonitor({read, render, complete, error, schedule = setTimeout, cancel = clearTimeout, delay = 2500}) {
  let timer;
  let revision = 0;

  function stop() {
    revision++;
    cancel(timer);
    timer = undefined;
  }

  function poll(job, context, expected) {
    timer = schedule(async () => {
      timer = undefined;
      let status;
      try {
        status = await read(job, context);
      } catch (failure) {
        if (expected !== revision) return;
        error(failure, "read", job);
        poll(job, context, expected);
        return;
      }
      if (expected !== revision) return;
      render(status);
      if (!status.terminal) {
        poll(status, context, expected);
        return;
      }
      try {
        await complete(status, context, () => expected === revision);
      } catch (failure) {
        if (expected === revision) error(failure, "refresh", status);
      }
    }, delay);
  }

  function watch(job, context) {
    stop();
    render(job);
    if (!job.terminal) poll(job, context, revision);
  }

  return {watch, stop};
}
