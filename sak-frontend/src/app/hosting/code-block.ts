import { Component, computed, DestroyRef, inject, input, signal } from '@angular/core';
import { Subscription, timer } from 'rxjs';

// how long the copy button says it copied
export const CODE_COPIED_MS = 2000;

// a file or a command to take over as it is, as a terminal window: its name in the bar with a
// copy button, the code below; what is copied can differ from what is shown, e.g. a secret that
// is shown masked
@Component({
  selector: 'app-code-block',
  styles: `
    // a terminal window, dark in light and dark mode alike
    :host {
      --terminal: #1d1c1a;
      --terminal-bar: #2b2a27;
      --terminal-text: #ece8dd;
      --terminal-muted: #9b968b;
      --terminal-prompt: #8fd18b;

      display: block;
      overflow: hidden;
      border-radius: 10px;
      background: var(--terminal);
      color: var(--terminal-text);
      box-shadow: 0 6px 16px -10px rgb(0 0 0 / 60%);
    }
    .bar {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 6px 8px 6px 12px;
      background: var(--terminal-bar);
      color: var(--terminal-muted);
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
      background: #ff5f57;
    }
    .dots span:nth-child(2) {
      background: #febc2e;
    }
    .dots span:nth-child(3) {
      background: #28c840;
    }
    .name {
      flex: 1;
      min-width: 0;
      overflow: hidden;
      font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .bar ::ng-deep button {
      display: inline-flex;
      flex: none;
      align-items: center;
      gap: 6px;
      padding: 4px 10px;
      border: 1px solid #4a4843;
      border-radius: 999px;
      background: transparent;
      color: var(--terminal-text);
      font: inherit;
      cursor: pointer;

      &:hover,
      &:focus-visible {
        border-color: var(--terminal-text);
      }
    }
    pre {
      margin: 0;
      padding: 12px 14px;
      overflow-x: auto;
      font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace;
      font-size: 0.85rem;
      line-height: 1.55;
    }
    .prompt {
      color: var(--terminal-prompt);
      user-select: none;
    }
    p {
      margin: 0;
      padding: 0 14px 12px;
      color: #f2b8b5;
    }
  `,
  template: `
    <div class="bar">
      <span class="dots" aria-hidden="true"><span></span><span></span><span></span></span>
      <span class="name">{{ name() }}</span>
      <ng-content />
      <button type="button" [attr.aria-label]="copyLabel()" (click)="copy()">
        <i
          class="fa-solid"
          [class.fa-check]="copied()"
          [class.fa-copy]="!copied()"
          aria-hidden="true"
        ></i>
        {{ copied() ? 'Copied' : 'Copy' }}
      </button>
    </div>
    <pre
      [attr.data-testid]="testId()"
    ><code>@for (line of lines(); track $index) {@if (line.prompt) {<span class="prompt">$ </span>}{{ line.text }}{{ '
' }}}</code></pre>
    @if (copyFailed()) {
      <p role="alert">The browser did not allow to copy, please select the text instead.</p>
    }
  `,
})
export class CodeBlock {
  readonly name = input.required<string>();
  // what is shown
  readonly code = input.required<string>();
  // what is copied, the shown code when missing
  readonly copyText = input<string | null>(null);
  readonly copyLabel = input.required<string>();
  readonly testId = input<string | null>(null);
  // a command: every line that does not continue the one before gets a prompt, which is not copied
  readonly command = input(false);

  protected readonly lines = computed(() => {
    const lines = this.code().split('\n');
    return lines.map((text, index) => ({
      text,
      prompt: this.command() && (index === 0 || !lines[index - 1].endsWith('\\')),
    }));
  });

  protected readonly copied = signal(false);
  protected readonly copyFailed = signal(false);

  private timer?: Subscription;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.timer?.unsubscribe());
  }

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.copyText() ?? this.code());
      this.copyFailed.set(false);
      this.copied.set(true);
      this.timer?.unsubscribe();
      this.timer = timer(CODE_COPIED_MS).subscribe(() => this.copied.set(false));
    } catch {
      this.copyFailed.set(true);
    }
  }
}
