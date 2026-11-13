import {
  afterNextRender,
  Component,
  ElementRef,
  Injector,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';
import { CopyButton } from '../shared/copy-button';
import { BucketStore } from './bucket-store';
import { s3Endpoint } from './buckets.service';

// the credentials tab of a bucket: the endpoint of the s3 api and the key pair that may access
// this bucket only; the keys are fetched when the tab opens, the secret stays hidden until it
// is shown or copied. The key pair can be replaced, e.g. after it leaked (SEC-12): it asks in its
// place first, Keep is the safe default (UX-4.1), as every client with the current one stops
@Component({
  selector: 'app-bucket-credentials',
  imports: [CopyButton, TranslatePipe],
  template: `
    <section class="card card-body" [attr.aria-label]="'hosting.credentials.label' | t">
      @if (store.secretError(); as error) {
        <p class="alert alert-danger" role="alert">{{ error }}</p>
      }
      <p class="text-body-secondary">
        {{ 'hosting.credentials.intro' | t }}
      </p>
      <dl class="row mb-0">
        <dt class="col-sm-3 fw-normal text-body-secondary">
          {{ 'hosting.credentials.endpoint' | t }}
        </dt>
        <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
          <code class="font-monospace text-break" data-testid="endpoint">{{ endpoint }}</code>
          <app-copy-button
            [text]="endpoint"
            [label]="'hosting.credentials.copy_endpoint' | t"
            (failed)="copyFailed.set($event)"
          />
        </dd>
        <dt class="col-sm-3 fw-normal text-body-secondary">
          {{ 'hosting.credentials.bucket' | t }}
        </dt>
        <dd class="col-sm-9">
          <code class="font-monospace text-break">{{ store.name() }}</code>
        </dd>
        @if (store.secret(); as secret) {
          <dt class="col-sm-3 fw-normal text-body-secondary">
            {{ 'hosting.credentials.access_key' | t }}
          </dt>
          <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
            <code class="font-monospace text-break" data-testid="access-key">{{
              secret.accessKeyId
            }}</code>
            <app-copy-button
              [text]="secret.accessKeyId"
              [label]="'hosting.credentials.copy_access_key' | t"
              (failed)="copyFailed.set($event)"
            />
          </dd>
          <dt class="col-sm-3 fw-normal text-body-secondary">
            {{ 'hosting.credentials.secret_key' | t }}
          </dt>
          <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
            <code class="font-monospace text-break" data-testid="secret-key">{{
              shown() ? secret.secretAccessKey : '••••••••••••••••••••'
            }}</code>
            <button
              type="button"
              class="btn btn-outline-secondary btn-sm"
              [attr.aria-label]="
                (shown()
                  ? 'hosting.credentials.hide_secret_key'
                  : 'hosting.credentials.show_secret_key'
                ) | t
              "
              [title]="
                (shown()
                  ? 'hosting.credentials.hide_secret_key'
                  : 'hosting.credentials.show_secret_key'
                ) | t
              "
              [attr.aria-pressed]="shown()"
              (click)="shown.set(!shown())"
            >
              <i
                class="fa-solid"
                [class.fa-eye-slash]="shown()"
                [class.fa-eye]="!shown()"
                aria-hidden="true"
              ></i>
            </button>
            <app-copy-button
              [text]="secret.secretAccessKey"
              [label]="'hosting.credentials.copy_secret_key' | t"
              (failed)="copyFailed.set($event)"
            />
          </dd>
        } @else if (store.retrievingSecret()) {
          <dt class="col-sm-3 fw-normal text-body-secondary">
            {{ 'hosting.credentials.keys' | t }}
          </dt>
          <dd class="col-sm-9 text-body-secondary">
            <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
            {{ 'shared.loading' | t }}
          </dd>
        }
      </dl>
      @if (copyFailed()) {
        <p class="alert alert-danger mb-0" role="alert">
          {{ 'hosting.copy_failed' | t }}
        </p>
      }
      @if (store.secretReplaced()) {
        <p class="alert alert-success mt-3 mb-0" role="status" data-testid="secret-replaced">
          {{ 'hosting.credentials.replaced' | t }}
        </p>
      }
      @if (store.secret()) {
        <div class="d-flex flex-wrap align-items-center justify-content-end gap-2 mt-3">
          @if (confirmingReplace()) {
            <span class="small">{{
              'hosting.credentials.replace_question' | t: { name: store.name() }
            }}</span>
            <div
              class="btn-group"
              role="group"
              [attr.aria-label]="'hosting.credentials.replace' | t"
            >
              <button type="button" class="btn btn-danger" (click)="replace()">
                {{ 'hosting.credentials.replace_confirm' | t }}
              </button>
              <button
                #keep
                type="button"
                class="btn btn-outline-secondary"
                (click)="confirmingReplace.set(false)"
              >
                {{ 'shared.keep' | t }}
              </button>
            </div>
          } @else {
            <button
              type="button"
              class="btn btn-outline-danger"
              data-testid="replace-secret"
              [disabled]="store.replacingSecret()"
              (click)="ask()"
            >
              @if (store.replacingSecret()) {
                <i class="fa-solid fa-spinner fa-spin me-1" aria-hidden="true"></i>
              } @else {
                <i class="fa-solid fa-rotate me-1" aria-hidden="true"></i>
              }
              {{ 'hosting.credentials.replace' | t }}
            </button>
          }
        </div>
      }
    </section>
  `,
})
export class BucketCredentials {
  protected readonly store = inject(BucketStore);

  protected readonly endpoint = s3Endpoint();
  protected readonly shown = signal(false);
  protected readonly copyFailed = signal(false);
  protected readonly confirmingReplace = signal(false);

  private readonly injector = inject(Injector);
  private readonly keep = viewChild<ElementRef<HTMLButtonElement>>('keep');

  constructor() {
    this.store.retrieveSecret();
  }

  // the question in place of the button, Keep has the focus
  protected ask(): void {
    this.confirmingReplace.set(true);
    afterNextRender(() => this.keep()?.nativeElement.focus(), { injector: this.injector });
  }

  protected replace(): void {
    this.confirmingReplace.set(false);
    this.shown.set(false);
    this.store.replaceSecret();
  }
}
