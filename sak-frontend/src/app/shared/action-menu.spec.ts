import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActionMenu } from './action-menu';

@Component({
  imports: [ActionMenu],
  template: `<app-action-menu name="Milk"
    ><a class="dropdown-item" role="menuitem">Open</a></app-action-menu
  >`,
})
class Host {}

describe('ActionMenu', () => {
  const MENU_HEIGHT = 100;

  beforeEach(() => {
    vi.spyOn(window, 'innerHeight', 'get').mockReturnValue(800);
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(MENU_HEIGHT);
  });

  afterEach(() => vi.restoreAllMocks());

  // opens the menu of a button whose top is at the given distance from the top of the window
  const openAt = async (top: number) => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    vi.spyOn(button, 'getBoundingClientRect').mockReturnValue(new DOMRect(900, top, 40, 40));
    button.click();
    await fixture.whenStable();
    return fixture.nativeElement.querySelector('[role=menu]') as HTMLElement;
  };

  it('opens below its button where the window has room for it', async () => {
    const menu = await openAt(100);
    expect(menu.classList).toContain('show');
    expect(menu.style.top).toBe('140px');
  });

  it('opens above its button on the last rows of a long list', async () => {
    const menu = await openAt(750);
    expect(menu.style.top).toBe(`${750 - MENU_HEIGHT}px`);
  });
});
