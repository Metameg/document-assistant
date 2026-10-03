export function initializePlotFallbacks() {
  for (const img of document.querySelectorAll(".plot-card img")) {
    const update = () => {
      const failed = !img.naturalWidth;
      img.parentElement.hidden = failed;
      img.closest("figure").querySelector(".plot-unavailable").hidden = !failed;
    };
    img.addEventListener("load", update);
    img.addEventListener("error", update);
    if (img.complete) update();
  }
}

