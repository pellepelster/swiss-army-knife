// the copy pill of a terminal block (layouts/shortcodes/install.html): copies the commands without
// their prompts and says so for a moment, or tells when the browser refuses the clipboard
document.addEventListener('click', async (event) => {
  const button = event.target.closest('.sak-code-block .copy');
  if (!button) {
    return;
  }
  const block = button.closest('.sak-code-block');
  const label = button.querySelector('.label');
  const icon = button.querySelector('i');
  try {
    await navigator.clipboard.writeText(block.dataset.copy);
    block.querySelector('.failed').hidden = true;
    label.textContent = 'Copied';
    icon.className = 'fa-solid fa-check';
    clearTimeout(button.copiedTimer);
    button.copiedTimer = setTimeout(() => {
      label.textContent = 'Copy';
      icon.className = 'fa-solid fa-copy';
    }, 2000);
  } catch {
    block.querySelector('.failed').hidden = false;
  }
});
