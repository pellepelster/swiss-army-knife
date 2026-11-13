import { Component, computed, input, signal } from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';
import { CopyButton } from '../shared/copy-button';

// a file or a command to copy, as a terminal window; what is copied can differ from what is
// shown, e.g. a masked secret
@Component({
  selector: 'app-code-block',
  imports: [CopyButton, TranslatePipe],
  styles: `
    // dark in both color schemes (the terminal tokens of sak-theme)
    :host {
      display: block;
      overflow: hidden;
      border-radius: var(--bs-border-radius-lg);
      background: var(--sak-terminal);
      color: var(--sak-terminal-ink);
      box-shadow: 0 6px 16px -10px rgb(0 0 0 / 60%);
    }
    .bar {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 8px 6px 12px;
      background: var(--sak-terminal-bar);
      color: var(--sak-terminal-muted);
      font-size: 0.85rem;
    }
    .dots {
      display: flex;
      gap: 6px;
      margin-right: 4px;
    }
    .dots span {
      width: 10px;
      height: 10px;
      border-radius: 50%;
    }
    .dots span:nth-child(1) {
      background: var(--sak-danger);
    }
    .dots span:nth-child(2) {
      background: var(--sak-warn);
    }
    .dots span:nth-child(3) {
      background: var(--sak-ok);
    }
    .name {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      font-family: var(--sak-font-mono);
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    pre {
      margin: 0;
      padding: 12px 14px;
      overflow-x: auto;
      font-family: var(--sak-font-mono);
      font-size: 0.85rem;
      line-height: 1.55;
    }
    .prompt {
      color: var(--sak-accent);
      user-select: none;
    }
    p {
      margin: 0;
      padding: 0 14px 12px;
      color: var(--sak-danger);
    }
  `,
  template: `
    <div class="bar">
      <span class="dots" aria-hidden="true"><span></span><span></span><span></span></span>
      <span class="name">{{ name() }}</span>
      <ng-content />
      <app-copy-button
        look="terminal"
        [text]="copyText() ?? code()"
        [label]="copyLabel()"
        (failed)="copyFailed.set($event)"
      />
    </div>
    <pre
      [attr.data-testid]="testId()"
    ><code>@for (line of lines(); track $index) {@if (line.prompt) {<span class="prompt">$ </span>}{{ line.text }}{{ '
' }}}</code></pre>
    @if (copyFailed()) {
      <p role="alert">{{ 'hosting.copy_failed' | t }}</p>
    }
  `,
})
export class CodeBlock {
  readonly name = input.required<string>();
  readonly code = input.required<string>();
  // the shown code when null
  readonly copyText = input<string | null>(null);
  readonly copyLabel = input.required<string>();
  readonly testId = input<string | null>(null);
  // a command: a prompt before every line that does not continue the one before, not copied
  readonly command = input(false);

  protected readonly lines = computed(() => {
    const lines = this.code().split('\n');
    return lines.map((text, index) => ({
      text,
      prompt: this.command() && (index === 0 || !lines[index - 1].endsWith('\\')),
    }));
  });

  protected readonly copyFailed = signal(false);
}
