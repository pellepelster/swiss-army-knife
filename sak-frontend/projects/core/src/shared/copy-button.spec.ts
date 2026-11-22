import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { COPIED_MS, CopyButton } from './copy-button';

@Component({
  imports: [CopyButton],
  template: `<app-copy-button
    text="secret"
    label="Copy the secret"
    (failed)="failed.set($event)"
  />`,
})
class Host {
  readonly failed = signal<boolean | null>(null);
}

describe('CopyButton', () => {
  const writeText = vi.fn();

  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    writeText.mockReset();
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
  });

  afterEach(() => vi.useRealTimers());

  const render = async () => {
    const fixture = TestBed.createComponent(Host);
    await fixture.whenStable();
    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    return { fixture, button };
  };

  it('copies the text and says so for a moment', async () => {
    writeText.mockResolvedValue(undefined);
    const { fixture, button } = await render();
    expect(button.getAttribute('aria-label')).toBe('Copy the secret');

    button.click();
    await fixture.whenStable();
    expect(writeText).toHaveBeenCalledWith('secret');
    expect(button.querySelector('.fa-check')).not.toBeNull();
    expect(fixture.componentInstance.failed()).toBe(false);

    await vi.advanceTimersByTimeAsync(COPIED_MS);
    await fixture.whenStable();
    expect(button.querySelector('.fa-copy')).not.toBeNull();
  });

  it('tells when the browser does not allow to copy', async () => {
    writeText.mockRejectedValue(new Error('denied'));
    const { fixture, button } = await render();

    button.click();
    await fixture.whenStable();
    expect(fixture.componentInstance.failed()).toBe(true);
    expect(button.querySelector('.fa-check')).toBeNull();
  });
});
