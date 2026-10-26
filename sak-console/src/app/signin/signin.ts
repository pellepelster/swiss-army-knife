import { Location } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';

// the target of the e-mailed link, exchanges its login token for a session
@Component({
  selector: 'app-signin',
  imports: [RouterLink],
  template: `
    @if (failed()) {
      <h1>Sign in failed</h1>
      <p role="alert">
        The login link is invalid, expired or was already used.
        <a routerLink="/login">Request a new one</a>.
      </p>
    } @else {
      <p>Signing in…</p>
    }
  `,
})
export class Signin implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly token = inject(ActivatedRoute).snapshot.queryParamMap.get('token');

  protected readonly failed = signal(false);

  ngOnInit(): void {
    // the login token never stays in the address bar or the history
    this.location.replaceState('/signin');

    if (!this.token) {
      this.failed.set(true);
      return;
    }

    this.auth.signIn(this.token).subscribe({
      next: (redirect) => void this.router.navigateByUrl(redirect, { replaceUrl: true }),
      error: () => this.failed.set(true),
    });
  }
}
