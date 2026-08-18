(function initializeTheme() {
  const savedTheme = window.localStorage.getItem("lexidex-theme");
  const systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  document.documentElement.dataset.theme = savedTheme || (systemDark ? "dark" : "light");
})();
