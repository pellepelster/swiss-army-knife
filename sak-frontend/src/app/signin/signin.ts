import { Location } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService, loginTokenEmail, TranslatePipe } from '@sak/core';

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

// the target of the e-mailed link, trades its login token for a session; the link of a login an
// android app asked for (client=grocery-android, ...) goes on to that app, unused here. A link this
// browser did not ask for, or one of another address than the one signed in, asks first whom it
// signs in (SEC-7)
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
              <a routerLink="/login">{{ 'signin.request_new' | t }}</a>{{
                'signin.failed_after' | t
              }}
            </p>
          } @else if (confirm(); as confirm) {
            <h1 class="h4 mb-3 text-break">
              {{ 'signin.confirm_title' | t: { email: confirm.email } }}
            </h1>
            <p class="text-body-secondary">{{ 'signin.confirm_elsewhere' | t }}</p>
            @if (confirm.current; as current) {
              <p class="text-break" data-testid="replaces">
                {{ 'signin.confirm_replaces' | t: { current: current } }}
              </p>
            }
            <div class="d-flex justify-content-end">
              <div class="btn-group" role="group" [attr.aria-label]="'shared.form_actions' | t">
                <a
                  class="btn btn-outline-secondary"
                  [routerLink]="confirm.current ? '/' : '/login'"
                  >{{ 'shared.cancel' | t }}</a
                >
                <button class="btn btn-primary" type="button" (click)="exchange()">
                  {{ 'login.sign_in' | t }}
                </button>
              </div>
            </div>
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
  // whom the link signs in, and who is signed in now, while the page asks (SEC-7)
  protected readonly confirm = signal<{ email: string; current: string | null } | null>(null);

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

    // at once only what this browser asked for and nobody else is signed in; a token without an
    // address is no token, its exchange fails
    const email = loginTokenEmail(this.token);
    const current = this.auth.signedInAs();
    const same = (a: string, b: string) => a.trim().toLowerCase() === b.trim().toLowerCase();
    if (
      email === null ||
      (this.auth.askedFor(email) && (current === null || same(current, email)))
    ) {
      this.exchange();
      return;
    }
    this.confirm.set({
      email,
      current: current !== null && !same(current, email) ? current : null,
    });
  }

  protected exchange(): void {
    this.confirm.set(null);
    this.auth.signIn(this.token!).subscribe({
      next: (redirect) => void this.router.navigateByUrl(redirect, { replaceUrl: true }),
      error: () => this.failed.set(true),
    });
  }
}
