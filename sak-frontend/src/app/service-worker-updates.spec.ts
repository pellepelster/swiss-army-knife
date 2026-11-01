import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { SwUpdate, UnrecoverableStateEvent, VersionEvent } from '@angular/service-worker';
import { Subject } from 'rxjs';
import { PAGE_LOADER, ServiceWorkerUpdates } from './service-worker-updates';

describe('ServiceWorkerUpdates', () => {
  const versionUpdates = new Subject<VersionEvent>();
  const unrecoverable = new Subject<UnrecoverableStateEvent>();
  const updates = {
    isEnabled: true,
    versionUpdates,
    unrecoverable,
    checkForUpdate: vi.fn(),
    activateUpdate: vi.fn(),
  };
  const page = { reload: vi.fn(), load: vi.fn() };

  const start = (enabled = true) => {
    updates.isEnabled = enabled;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: SwUpdate, useValue: updates },
        { provide: PAGE_LOADER, useValue: page },
      ],
    });
    TestBed.inject(ServiceWorkerUpdates).start();
  };

  beforeEach(() => {
    vi.clearAllMocks();
    updates.checkForUpdate.mockResolvedValue(false);
    updates.activateUpdate.mockResolvedValue(true);
  });

  it('reloads the page when the worker can no longer serve its version', () => {
    start();
    unrecoverable.next({ type: 'UNRECOVERABLE_STATE', reason: 'files evicted' });
    expect(page.reload).toHaveBeenCalled();
  });

  it('loads a new version at the next navigation, not before', async () => {
    start();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/notes');
    expect(page.load).not.toHaveBeenCalled();

    versionUpdates.next({
      type: 'VERSION_READY',
      currentVersion: { hash: 'old' },
      latestVersion: { hash: 'new' },
    });
    expect(page.load).not.toHaveBeenCalled();

    void router.navigateByUrl('/static-hosting');
    await vi.waitFor(() => expect(page.load).toHaveBeenCalledWith('/static-hosting'));
    expect(updates.activateUpdate).toHaveBeenCalled();
  });

  it('looks for a new version whenever the app comes back into view', () => {
    start();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(updates.checkForUpdate).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(updates.checkForUpdate).toHaveBeenCalledTimes(1);
    // the document of jsdom again, the specs that follow may share it
    Reflect.deleteProperty(document, 'visibilityState');
  });

  it('checks for a new version when asked and loads it at once', async () => {
    start();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/notes');
    const service = TestBed.inject(ServiceWorkerUpdates);
    expect(await service.check()).toBe('current');
    expect(page.load).not.toHaveBeenCalled();

    updates.checkForUpdate.mockResolvedValue(true);
    expect(await service.check()).toBe('updating');
    expect(updates.activateUpdate).toHaveBeenCalled();
    expect(page.load).toHaveBeenCalledWith('/notes');

    updates.checkForUpdate.mockRejectedValue(new Error('offline'));
    expect(await service.check()).toBe('failed');
  });

  it('a check without a service worker finds nothing', async () => {
    start(false);
    expect(await TestBed.inject(ServiceWorkerUpdates).check()).toBe('unavailable');
    expect(updates.checkForUpdate).not.toHaveBeenCalled();
  });

  it('does nothing without a service worker', () => {
    start(false);
    unrecoverable.next({ type: 'UNRECOVERABLE_STATE', reason: 'files evicted' });
    document.dispatchEvent(new Event('visibilitychange'));
    expect(page.reload).not.toHaveBeenCalled();
    expect(updates.checkForUpdate).not.toHaveBeenCalled();
  });
});
