import { Component, input } from '@angular/core';
import { TodoPriority } from './todo.service';

// the icon of a priority (TODO-5): a high one an arrow up in the danger color, a normal one a dash,
// a low one an arrow down, both muted; the same in the rows and in the menus of the priority. A
// decoration, the text next to it or the name of the toggle says the priority
@Component({
  selector: 'app-priority-icon',
  host: { class: 'd-inline-block' },
  template: `<i
    class="fa-solid fa-fw"
    [class.fa-arrow-up]="priority() === 'high'"
    [class.fa-minus]="priority() === 'normal'"
    [class.fa-arrow-down]="priority() === 'low'"
    [class.text-danger]="priority() === 'high'"
    [class.text-body-secondary]="priority() !== 'high'"
    aria-hidden="true"
  ></i>`,
})
export class PriorityIcon {
  readonly priority = input.required<TodoPriority>();
}
