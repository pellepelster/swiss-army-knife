import { DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-home',
  imports: [DatePipe],
  template: `
    <h1>Console</h1>
    @if (me(); as me) {
      <p data-testid="signed-in-as">Signed in as <strong>{{ me.email }}</strong></p>
      <p>The session expires {{ me.expiresAt | date: 'medium' }}.</p>
    }
    <button type="button" (click)="logout()">Sign out</button>
  `,
})
export class Home {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly me = toSignal(this.auth.me());

  protected logout(): void {
    this.auth.logout();
    void this.router.navigate(['/login']);
  }
}
