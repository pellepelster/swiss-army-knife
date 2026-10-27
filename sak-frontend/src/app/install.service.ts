import { Injectable, signal } from '@angular/core';

// what chrome hands over when the app can be installed, not in the dom types
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

// installs the notes as an app, e.g. into the app drawer of android; the browser tells when it
// can, which is never in the installed app itself
@Injectable({ providedIn: 'root' })
export class InstallService {
  private prompt: BeforeInstallPromptEvent | null = null;

  readonly canInstall = signal(false);

  constructor() {
    window.addEventListener('beforeinstallprompt', (event) => {
      // the browser's own hint stays away, the app offers the installation itself
      event.preventDefault();
      this.prompt = event as BeforeInstallPromptEvent;
      this.canInstall.set(true);
    });
    window.addEventListener('appinstalled', () => this.forget());
  }

  // the browser asks the user, a prompt can only be shown once
  async install(): Promise<void> {
    const prompt = this.prompt;
    if (!prompt) return;
    this.forget();
    await prompt.prompt();
    await prompt.userChoice;
  }

  private forget(): void {
    this.prompt = null;
    this.canInstall.set(false);
  }
}
