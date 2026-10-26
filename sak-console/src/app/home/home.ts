import { DatePipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';

@Component({
  selector: 'app-home',
  imports: [DatePipe],
  template: `
    <div class="d-flex align-items-center justify-content-between mb-4">
      <h1 class="h3 mb-0">Console</h1>
      <button class="btn btn-outline-secondary btn-sm" type="button" (click)="logout()">
        Sign out
      </button>
    </div>
    @if (me(); as me) {
      <div class="card">
        <div class="card-body">
          <p class="mb-1" data-testid="signed-in-as">Signed in as <strong>{{ me.email }}</strong></p>
          <p class="mb-0 text-body-secondary small">
            The session expires {{ me.expiresAt | date: 'medium' }}.
          </p>
        </div>
      </div>
    }
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
