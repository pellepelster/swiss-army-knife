import { Location } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { TranslatePipe } from '../i18n/i18n';

// the android apps by the client of their login link (LoginClient of sak-auth): the package, also the
// scheme of the signin link, and the texts of the page that hands the link on
export const ANDROID_APPS = {
  'grocery-android': {
    pkg: 'io.pelle.sak.grocery',
    title: 'signin.grocery_title',
    text: 'signin.grocery_text',
  },
  'notes-android': {
    pkg: 'io.pelle.sak.notes',
    title: 'signin.notes_title',
    text: 'signin.notes_text',
  },
  'todo-android': {
    pkg: 'io.pelle.sak.todo',
    title: 'signin.todo_title',
    text: 'signin.todo_text',
  },
} as const;

export type AndroidClient = keyof typeof ANDROID_APPS;

// the link that opens the android app of the client with the login token; an intent of android that
// names the package, so no other app gets the token
export function androidAppLink(client: AndroidClient, token: string): string {
  const pkg = ANDROID_APPS[client].pkg;
  return `intent://signin?token=${encodeURIComponent(token)}#Intent;scheme=${pkg};package=${pkg};end`;
}

// the target of the e-mailed link, exchanges its login token for a session; the link of a login an
// android app asked for (client=grocery-android, notes-android, todo-android) is handed on to that app, the page
// does not use the token
@Component({
  selector: 'app-signin',
  imports: [RouterLink, TranslatePipe],
  host: { class: 'd-block container' },
  template: `
    <div class="row min-vh-100 align-items-center justify-content-center py-3">
      <main class="col-12 col-sm-8 col-md-6 col-lg-4">
        <div class="card card-body">
          @if (appLink(); as link) {
            <h1 class="h4 mb-3">{{ app()!.title | t }}</h1>
            <p>{{ app()!.text | t }}</p>
            <a class="btn btn-primary" [href]="link">{{ 'signin.open_in_app' | t }}</a>
          } @else if (failed()) {
            <h1 class="h4 mb-3">{{ 'signin.failed_title' | t }}</h1>
            <p class="alert alert-danger mb-0" role="alert">
              {{ 'login.link_invalid' | t }}
              <a routerLink="/login">{{ 'signin.request_new' | t }}</a
              >{{ 'signin.failed_after' | t }}
            </p>
          } @else {
            <p class="mb-0">{{ 'login.signing_in' | t }}</p>
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
  // the android app that asked for the login, and the link that opens it
  protected readonly app = signal<(typeof ANDROID_APPS)[AndroidClient] | null>(null);
  protected readonly appLink = signal<SafeUrl | null>(null);

  ngOnInit(): void {
    // the login token never stays in the address bar or the history
    this.location.replaceState('/signin');

    if (!this.token) {
      this.failed.set(true);
      return;
    }
    const client = this.params.get('client');
    if (client !== null && Object.hasOwn(ANDROID_APPS, client)) {
      // a fixed scheme and package, the token encoded: the only url angular must not sanitize
      this.app.set(ANDROID_APPS[client as AndroidClient]);
      this.appLink.set(
        this.sanitizer.bypassSecurityTrustUrl(androidAppLink(client as AndroidClient, this.token)),
      );
      return;
    }

    this.auth.signIn(this.token).subscribe({
      next: (redirect) => void this.router.navigateByUrl(redirect, { replaceUrl: true }),
      error: () => this.failed.set(true),
    });
  }
}
