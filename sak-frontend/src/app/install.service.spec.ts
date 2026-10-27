import { TestBed } from '@angular/core/testing';
import { InstallService } from './install.service';

describe('InstallService', () => {
  const promptEvent = () => {
    const event = new Event('beforeinstallprompt', { cancelable: true }) as Event & {
      prompt: ReturnType<typeof vi.fn>;
      userChoice: Promise<{ outcome: string }>;
    };
    event.prompt = vi.fn().mockResolvedValue(undefined);
    event.userChoice = Promise.resolve({ outcome: 'accepted' });
    return event;
  };

  it('offers the installation once the browser allows it and asks the browser to install', async () => {
    const installer = TestBed.inject(InstallService);
    expect(installer.canInstall()).toBe(false);

    const event = promptEvent();
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    expect(installer.canInstall()).toBe(true);

    await installer.install();
    expect(event.prompt).toHaveBeenCalled();
    expect(installer.canInstall()).toBe(false);
  });

  it('offers nothing once the app is installed', () => {
    const installer = TestBed.inject(InstallService);
    window.dispatchEvent(promptEvent());
    window.dispatchEvent(new Event('appinstalled'));
    expect(installer.canInstall()).toBe(false);
  });
});
