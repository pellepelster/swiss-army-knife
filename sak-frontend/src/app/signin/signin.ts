import { Location } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';

// the package of sak-grocery-android, also the scheme of its signin link
export const GROCERY_ANDROID_APP = 'io.pelle.sak.grocery';

// the link that opens sak-grocery-android with the login token; an intent of android that names the
// package, so no other app gets the token
export function groceryAppLink(token: string): string {
  return `intent://signin?token=${encodeURIComponent(token)}#Intent;scheme=${GROCERY_ANDROID_APP};package=${GROCERY_ANDROID_APP};end`;
}

// the target of the e-mailed link, exchanges its login token for a session; the link of a login the
// android app asked for (client=android) is handed on to the app, the page does not use the token
@Component({
  selector: 'app-signin',
  imports: [RouterLink],
  host: { class: 'd-block container' },
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-8 col-md-6 col-lg-4">
        <div class="card card-body">
          @if (appLink(); as link) {
            <h1 class="h4 mb-3">Sign in to the grocery app</h1>
            <p>This link signs you in to the SAK grocery app. Open it on the phone with the app.</p>
            <a class="btn btn-primary" [href]="link">Open in the app</a>
          } @else if (failed()) {
            <h1 class="h4 mb-3">Sign in failed</h1>
            <p class="alert alert-danger mb-0" role="alert">
              The login link is invalid, expired or was already used.
              <a routerLink="/login">Request a new one</a>.
            </p>
          } @else {
            <p class="mb-0">Signing in…</p>
          }
        </div>
      </main>
    </div>
  `,
})
export class Signin implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly params = inject(ActivatedRoute).snapshot.queryParamMap;
  private readonly token = this.params.get('token');

  protected readonly failed = signal(false);
  // the link that opens the android app, for a login it asked for
  protected readonly appLink = signal<SafeUrl | null>(null);

  ngOnInit(): void {
    // the login token never stays in the address bar or the history
    this.location.replaceState('/signin');

    if (!this.token) {
      this.failed.set(true);
      return;
    }
    if (this.params.get('client') === 'android') {
      // a fixed scheme and package, the token encoded: the only url angular must not sanitize
      this.appLink.set(this.sanitizer.bypassSecurityTrustUrl(groceryAppLink(this.token)));
      return;
    }

    this.auth.signIn(this.token).subscribe({
      next: (redirect) => void this.router.navigateByUrl(redirect, { replaceUrl: true }),
      error: () => this.failed.set(true),
    });
  }
}
