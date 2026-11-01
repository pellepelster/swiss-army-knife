import {
  afterNextRender,
  Component,
  computed,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { I18n, TranslatePipe } from '../i18n/i18n';

// a menu behind a ⋮ button, at the right end of a row of a list or of the header of a page; its
// entries come as content, e.g. `<a class="dropdown-item" role="menuitem">`. Given a question, its
// last entry deletes what the row shows: it asks inline in place of the button, Keep is the safe
// default (UX-4.1, WEB-3); a delete that does not apply stays disabled and says why (UX-8). The menu is fixed to the window, so a
// card or a scrolling table does not cut it off; it closes on a click elsewhere, Escape, a scroll
@Component({
  selector: 'app-action-menu',
  imports: [TranslatePipe],
  host: {
    class: 'd-flex align-items-center justify-content-end flex-shrink-0 position-relative',
    // above a stretched link of its row, and when open above the rows below
    '[style.z-index]': 'open() ? 1000 : 1',
    '(document:click)': 'clickedOutside($event)',
    '(window:scroll)': 'close()',
    '(window:resize)': 'close()',
    '(keydown.escape)': 'close(true)',
  },
  template: `
    @if (confirming()) {
      <div class="d-flex flex-wrap align-items-center justify-content-end gap-2">
        <span class="small">{{ question() }}</span>
        <div
          class="btn-group"
          role="group"
          [attr.aria-label]="'shared.action_menu.delete' | t: { name: name() ?? '' }"
        >
          <button type="button" class="btn btn-danger btn-sm" (click)="confirm()">
            {{ 'shared.delete' | t }}
          </button>
          <button type="button" class="btn btn-outline-secondary btn-sm" (click)="keep()">
            {{ 'shared.keep' | t }}
          </button>
        </div>
      </div>
    }
    <button
      #toggle
      type="button"
      class="btn btn-link link-body-emphasis"
      [class.d-none]="confirming()"
      aria-haspopup="menu"
      [attr.aria-expanded]="open()"
      [attr.aria-label]="label()"
      [title]="'shared.action_menu.actions' | t"
      (click)="toggleMenu()"
    >
      <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
    </button>
    <!-- always there, so the entries given as content stay; shown while open -->
    <div
      #menu
      class="dropdown-menu dropdown-menu-end position-fixed"
      [class.show]="open()"
      role="menu"
      [attr.aria-label]="label()"
      [style.top.px]="top()"
      [style.right.px]="right()"
      (click)="close()"
    >
      <ng-content />
      @if (question()) {
        <button
          type="button"
          role="menuitem"
          class="dropdown-item"
          [class.text-danger]="!deleteDisabled()"
          [disabled]="!!deleteDisabled()"
          [title]="deleteDisabled() ?? ''"
          (click)="ask()"
        >
          <i class="fa-solid fa-trash me-2" aria-hidden="true"></i>{{ 'shared.delete' | t }}
        </button>
      }
    </div>
  `,
})
export class ActionMenu {
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly injector = inject(Injector);
  private readonly i18n = inject(I18n);

  // what the row shows, for the labels; none for the menu of a page
  readonly name = input<string | null>(null);
  // what the delete asks before it is done; none for a menu without a delete
  readonly question = input<string | null>(null);
  // why the delete does not apply to the row, null when it does
  readonly deleteDisabled = input<string | null>(null);
  // the delete was confirmed
  readonly deleted = output<void>();

  private readonly toggle = viewChild.required<ElementRef<HTMLButtonElement>>('toggle');
  private readonly menu = viewChild.required<ElementRef<HTMLElement>>('menu');

  protected readonly label = computed(() => {
    const name = this.name();
    return name === null
      ? this.i18n.t('shared.action_menu.menu')
      : this.i18n.t('shared.action_menu.actions_of', { name });
  });

  protected readonly open = signal(false);
  protected readonly confirming = signal(false);
  // where the menu shows, under the button and aligned to its right end
  protected readonly top = signal(0);
  protected readonly right = signal(0);

  protected toggleMenu(): void {
    if (this.open()) {
      this.close();
      return;
    }
    const rect = this.toggle().nativeElement.getBoundingClientRect();
    this.top.set(rect.bottom);
    this.right.set(document.documentElement.clientWidth - rect.right);
    this.open.set(true);
    afterNextRender(
      () =>
        this.menu()
          .nativeElement.querySelector<HTMLElement>('[role=menuitem]:not(:disabled)')
          ?.focus(),
      { injector: this.injector },
    );
  }

  protected close(focus = false): void {
    if (!this.open()) return;
    this.open.set(false);
    if (focus) this.toggle().nativeElement.focus();
  }

  protected clickedOutside(event: Event): void {
    if (!this.host.nativeElement.contains(event.target as Node)) this.close();
  }

  protected ask(): void {
    this.open.set(false);
    this.confirming.set(true);
  }

  protected keep(): void {
    this.confirming.set(false);
    afterNextRender(() => this.toggle().nativeElement.focus(), { injector: this.injector });
  }

  protected confirm(): void {
    this.confirming.set(false);
    this.deleted.emit();
  }
}
